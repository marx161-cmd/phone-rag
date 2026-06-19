// Copyright 2025 The ODML Authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include <chrono>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <iterator>
#include <sstream>
#include <string>
#include <vector>

#include "absl/status/status.h"  // from @com_google_absl
#include "absl/strings/str_cat.h"  // from @com_google_absl
#include "absl/strings/string_view.h"  // from @com_google_absl
#include "absl/types/span.h"  // from @com_google_absl
#include "litert/cc/litert_compiled_model.h"  // from @litert
#include "litert/cc/litert_element_type.h"  // from @litert
#include "litert/cc/litert_environment.h"  // from @litert
#include "litert/cc/litert_environment_options.h"  // from @litert
#include "litert/cc/litert_macros.h"  // from @litert
#include "litert/cc/litert_model.h"  // from @litert
#include "litert/cc/litert_options.h"  // from @litert
#include "runtime/components/sentencepiece_tokenizer.h"
#include "runtime/util/convert_tensor_buffer.h"
#include "runtime/util/status_macros.h"

namespace {

struct ProbeConfig {
  std::string model_path = "/data/local/tmp/embeddinggemma/model.tflite";
  std::string dispatch_dir = "/data/local/tmp/poll-e-worker-test";
  std::string signature = "embed_512";
  std::string tokens_file = "/data/local/tmp/embeddinggemma/tokens.txt";
  std::string tokenizer_path = "/data/local/tmp/embeddinggemma/sentencepiece.model";
  std::string text;
  std::string text_file;
  std::string output_file;
  bool json_output = false;
  bool worker = false;
};

void PrintUsage(const char* argv0) {
  std::cerr << "Usage: " << argv0
            << " [--model_path=PATH] [--dispatch_dir=DIR]"
               " [--signature=embed_512] [--tokens_file=PATH]"
               " [--tokenizer_path=PATH] [--text=TEXT] [--text_file=PATH]"
               " [--output_file=PATH] [--json_output] [--worker]\n";
}

bool ConsumeArg(absl::string_view arg, absl::string_view name,
                std::string* value) {
  const std::string prefix = absl::StrCat("--", name, "=");
  if (!arg.starts_with(prefix)) {
    return false;
  }
  *value = std::string(arg.substr(prefix.size()));
  return true;
}

absl::StatusOr<ProbeConfig> ParseArgs(int argc, char** argv) {
  ProbeConfig config;
  for (int i = 1; i < argc; ++i) {
    absl::string_view arg(argv[i]);
    if (arg == "--help" || arg == "-h") {
      PrintUsage(argv[0]);
      return absl::CancelledError("help requested");
    }
    if (ConsumeArg(arg, "model_path", &config.model_path) ||
        ConsumeArg(arg, "dispatch_dir", &config.dispatch_dir) ||
        ConsumeArg(arg, "signature", &config.signature) ||
        ConsumeArg(arg, "tokens_file", &config.tokens_file) ||
        ConsumeArg(arg, "tokenizer_path", &config.tokenizer_path) ||
        ConsumeArg(arg, "text", &config.text) ||
        ConsumeArg(arg, "text_file", &config.text_file) ||
        ConsumeArg(arg, "output_file", &config.output_file)) {
      continue;
    }
    if (arg == "--json_output") {
      config.json_output = true;
      continue;
    }
    if (arg == "--worker") {
      config.worker = true;
      continue;
    }
    return absl::InvalidArgumentError(absl::StrCat("Unknown argument: ", arg));
  }
  return config;
}

absl::StatusOr<std::vector<int32_t>> TokensFromText(
    litert::lm::SentencePieceTokenizer& tokenizer, const std::string& text,
    int seq_len) {
  LITERT_ASSIGN_OR_RETURN(auto ids, tokenizer.TextToTokenIds(text));
  const auto& processor = tokenizer.GetProcessor();
  std::vector<int32_t> tokens;
  tokens.reserve(seq_len);
  if (processor.bos_id() >= 0) {
    tokens.push_back(processor.bos_id());
  }
  const int reserved_for_eos = processor.eos_id() >= 0 ? 1 : 0;
  const int max_body = std::max(0, seq_len - static_cast<int>(tokens.size()) -
                                      reserved_for_eos);
  for (int i = 0; i < ids.size() && i < max_body; ++i) {
    tokens.push_back(ids[i]);
  }
  if (processor.eos_id() >= 0 && tokens.size() < seq_len) {
    tokens.push_back(processor.eos_id());
  }
  const int pad_id = processor.pad_id() >= 0 ? processor.pad_id() : 0;
  while (tokens.size() < seq_len) {
    tokens.push_back(pad_id);
  }
  return tokens;
}

absl::StatusOr<std::vector<int32_t>> ReadTokens(const std::string& path) {
  std::ifstream input(path);
  if (!input) {
    return absl::NotFoundError(absl::StrCat("Failed to open ", path));
  }
  std::vector<int32_t> tokens;
  int64_t token = 0;
  while (input >> token) {
    tokens.push_back(static_cast<int32_t>(token));
  }
  if (tokens.empty()) {
    return absl::InvalidArgumentError("Token file is empty.");
  }
  return tokens;
}

absl::Status RunEmbedding(
    litert::CompiledModel& compiled_model, const std::string& signature,
    absl::Span<const int32_t> tokens, std::vector<float>* output) {
  LITERT_ASSIGN_OR_RETURN(auto input_buffers,
                          compiled_model.CreateInputBuffers(signature));
  LITERT_ASSIGN_OR_RETURN(auto output_buffers,
                          compiled_model.CreateOutputBuffers(signature));
  if (input_buffers.size() != 1) {
    return absl::InvalidArgumentError(
        absl::StrCat("Expected one input, got ", input_buffers.size()));
  }
  if (output_buffers.size() != 1) {
    return absl::InvalidArgumentError(
        absl::StrCat("Expected one output, got ", output_buffers.size()));
  }
  LITERT_RETURN_IF_ERROR(input_buffers[0].Write<int32_t>(tokens));
  LITERT_RETURN_IF_ERROR(
      compiled_model.Run(signature, input_buffers, output_buffers));
  LITERT_ASSIGN_OR_RETURN(*output,
                          litert::lm::CopyFromTensorBuffer<float>(
                              output_buffers[0]));
  return absl::OkStatus();
}

absl::Status ValidateEmbedding(const std::vector<float>& output) {
  double sum_abs = 0.0;
  int finite_count = 0;
  for (float value : output) {
    if (std::isfinite(value)) {
      ++finite_count;
    }
    sum_abs += std::fabs(value);
  }
  if (sum_abs == 0.0 || finite_count != output.size()) {
    return absl::InternalError("Output failed nonzero/finite smoke check.");
  }
  return absl::OkStatus();
}

absl::StatusOr<std::string> ReadTextFile(const std::string& path) {
  std::ifstream input(path);
  if (!input) {
    return absl::NotFoundError(absl::StrCat("Failed to open ", path));
  }
  return std::string(std::istreambuf_iterator<char>(input),
                     std::istreambuf_iterator<char>());
}

absl::StatusOr<std::vector<int32_t>> TokensFromText(const ProbeConfig& config,
                                                    int seq_len) {
  std::string text = config.text;
  if (text.empty() && !config.text_file.empty()) {
    LITERT_ASSIGN_OR_RETURN(text, ReadTextFile(config.text_file));
  }
  if (text.empty()) {
    return ReadTokens(config.tokens_file);
  }

  LITERT_ASSIGN_OR_RETURN(
      auto tokenizer,
      litert::lm::SentencePieceTokenizer::CreateFromFile(
          config.tokenizer_path));
  LITERT_ASSIGN_OR_RETURN(auto ids, tokenizer->TextToTokenIds(text));
  const auto& processor = tokenizer->GetProcessor();
  std::vector<int32_t> tokens;
  tokens.reserve(seq_len);
  if (processor.bos_id() >= 0) {
    tokens.push_back(processor.bos_id());
  }
  const int reserved_for_eos = processor.eos_id() >= 0 ? 1 : 0;
  const int max_body = std::max(0, seq_len - static_cast<int>(tokens.size()) -
                                      reserved_for_eos);
  for (int i = 0; i < ids.size() && i < max_body; ++i) {
    tokens.push_back(ids[i]);
  }
  if (processor.eos_id() >= 0 && tokens.size() < seq_len) {
    tokens.push_back(processor.eos_id());
  }
  const int pad_id = processor.pad_id() >= 0 ? processor.pad_id() : 0;
  while (tokens.size() < seq_len) {
    tokens.push_back(pad_id);
  }
  return tokens;
}

std::string DimsToString(absl::Span<const litert::Layout::Dim> dims) {
  std::ostringstream out;
  out << "[";
  for (int i = 0; i < dims.size(); ++i) {
    if (i > 0) {
      out << ", ";
    }
    out << dims[i];
  }
  out << "]";
  return out.str();
}

std::string BuildEmbeddingJson(const std::vector<float>& output,
                               int64_t run_ms, int64_t compile_ms) {
  std::ostringstream json;
  json << "{\"ok\":true,\"dimensions\":" << output.size()
       << ",\"embedding\":[";
  for (int i = 0; i < output.size(); ++i) {
    if (i > 0) {
      json << ",";
    }
    json << output[i];
  }
  json << "],\"run_ms\":" << run_ms << ",\"compile_ms\":" << compile_ms
       << "}";
  return json.str();
}

absl::Status RunProbe(const ProbeConfig& config) {
  std::cout << "model_path=" << config.model_path << "\n";
  std::cout << "dispatch_dir=" << config.dispatch_dir << "\n";
  std::cout << "signature=" << config.signature << "\n";
  std::cout << "tokens_file=" << config.tokens_file << "\n";
  std::cout << "tokenizer_path=" << config.tokenizer_path << "\n";

  std::vector<litert::EnvironmentOptions::Option> env_options;
  env_options.push_back(litert::EnvironmentOptions::Option{
      litert::EnvironmentOptions::Tag::kDispatchLibraryDir,
      absl::string_view(config.dispatch_dir)});

  auto compile_start = std::chrono::steady_clock::now();
  LITERT_ASSIGN_OR_RETURN(
      auto env, litert::Environment::Create(
                    litert::EnvironmentOptions(env_options)));
  LITERT_ASSIGN_OR_RETURN(auto model,
                          litert::Model::CreateFromFile(config.model_path));

  std::cout << "num_signatures=" << model.GetNumSignatures() << "\n";
  for (int i = 0; i < model.GetNumSignatures(); ++i) {
    LITERT_ASSIGN_OR_RETURN(auto signature, model.GetSignature(i));
    std::cout << "signature[" << i << "]=" << signature.Key() << "\n";
    std::cout << "  inputs:";
    for (auto name : signature.InputNames()) {
      std::cout << " " << name;
    }
    std::cout << "\n  outputs:";
    for (auto name : signature.OutputNames()) {
      std::cout << " " << name;
    }
    std::cout << "\n";
  }

  LITERT_ASSIGN_OR_RETURN(auto options, litert::Options::Create());
  options.SetHardwareAccelerators(litert::HwAccelerators::kNpu);
#if defined(__ANDROID__)
  LITERT_ASSIGN_OR_RETURN(auto& google_tensor_options,
                          options.GetGoogleTensorOptions());
  (void)google_tensor_options;
#endif
  LITERT_ASSIGN_OR_RETURN(
      auto compiled_model,
      litert::CompiledModel::Create(env, config.model_path, options));
  auto compile_end = std::chrono::steady_clock::now();

  LITERT_ASSIGN_OR_RETURN(auto input_buffers,
                          compiled_model.CreateInputBuffers(config.signature));
  LITERT_ASSIGN_OR_RETURN(auto output_buffers,
                          compiled_model.CreateOutputBuffers(config.signature));
  if (input_buffers.size() != 1) {
    return absl::InvalidArgumentError(
        absl::StrCat("Expected one input, got ", input_buffers.size()));
  }
  if (output_buffers.size() != 1) {
    return absl::InvalidArgumentError(
        absl::StrCat("Expected one output, got ", output_buffers.size()));
  }

  LITERT_ASSIGN_OR_RETURN(auto input_type, input_buffers[0].TensorType());
  LITERT_ASSIGN_OR_RETURN(auto output_type, output_buffers[0].TensorType());
  std::cout << "input_dtype=" << static_cast<int>(input_type.ElementType())
            << " input_dims="
            << DimsToString(input_type.Layout().Dimensions()) << "\n";
  std::cout << "output_dtype=" << static_cast<int>(output_type.ElementType())
            << " output_dims="
            << DimsToString(output_type.Layout().Dimensions()) << "\n";

  if (input_type.ElementType() != litert::ElementType::Int32) {
    return absl::InvalidArgumentError("Input is not int32.");
  }
  if (output_type.ElementType() != litert::ElementType::Float32) {
    return absl::InvalidArgumentError("Output is not float32.");
  }
  LITERT_ASSIGN_OR_RETURN(auto input_elements,
                          input_type.Layout().NumElements());
  LITERT_ASSIGN_OR_RETURN(auto tokens,
                          TokensFromText(config, input_elements));
  std::cout << "tokens=" << tokens.size() << "\n";
  if (tokens.size() != input_elements) {
    return absl::InvalidArgumentError(absl::StrCat(
        "Token count ", tokens.size(), " does not match input elements ",
        input_elements));
  }

  LITERT_RETURN_IF_ERROR(input_buffers[0].Write<int32_t>(tokens));

  auto run_start = std::chrono::steady_clock::now();
  LITERT_RETURN_IF_ERROR(
      compiled_model.Run(config.signature, input_buffers, output_buffers));
  auto run_end = std::chrono::steady_clock::now();

  LITERT_ASSIGN_OR_RETURN(auto output,
                          litert::lm::CopyFromTensorBuffer<float>(
                              output_buffers[0]));
  double sum_abs = 0.0;
  float max_abs = 0.0f;
  int finite_count = 0;
  for (float value : output) {
    if (std::isfinite(value)) {
      ++finite_count;
    }
    float abs_value = std::fabs(value);
    sum_abs += abs_value;
    if (abs_value > max_abs) {
      max_abs = abs_value;
    }
  }

  std::cout << "output_elements=" << output.size() << "\n";
  std::cout << "finite_count=" << finite_count << "\n";
  std::cout << "sum_abs=" << sum_abs << "\n";
  std::cout << "max_abs=" << max_abs << "\n";
  std::cout << "first_values=";
  for (int i = 0; i < output.size() && i < 8; ++i) {
    if (i > 0) {
      std::cout << ", ";
    }
    std::cout << output[i];
  }
  std::cout << "\n";
  const int64_t run_ms =
      std::chrono::duration_cast<std::chrono::milliseconds>(run_end -
                                                            run_start)
          .count();
  const int64_t compile_ms =
      std::chrono::duration_cast<std::chrono::milliseconds>(compile_end -
                                                            compile_start)
          .count();
  if (config.json_output || !config.output_file.empty()) {
    std::string json = BuildEmbeddingJson(output, run_ms, compile_ms);
    if (!config.output_file.empty()) {
      std::ofstream out(config.output_file);
      if (!out) {
        return absl::InternalError(
            absl::StrCat("Failed to open output file: ", config.output_file));
      }
      out << json << "\n";
    }
    if (config.json_output) {
      std::cout << "EMBEDDING_JSON:" << json << "\n";
    }
  }
  std::cout << "compile_ms="
            << compile_ms
            << "\n";
  std::cout << "run_ms="
            << run_ms
            << "\n";
  if (sum_abs == 0.0 || finite_count != output.size()) {
    return absl::InternalError(
        "Output failed nonzero/finite smoke check.");
  }
  return absl::OkStatus();
}

absl::Status RunWorker(const ProbeConfig& config) {
  std::vector<litert::EnvironmentOptions::Option> env_options;
  env_options.push_back(litert::EnvironmentOptions::Option{
      litert::EnvironmentOptions::Tag::kDispatchLibraryDir,
      absl::string_view(config.dispatch_dir)});

  LITERT_ASSIGN_OR_RETURN(
      auto env, litert::Environment::Create(
                    litert::EnvironmentOptions(env_options)));
  LITERT_ASSIGN_OR_RETURN(auto options, litert::Options::Create());
  options.SetHardwareAccelerators(litert::HwAccelerators::kNpu);
#if defined(__ANDROID__)
  LITERT_ASSIGN_OR_RETURN(auto& google_tensor_options,
                          options.GetGoogleTensorOptions());
  (void)google_tensor_options;
#endif
  LITERT_ASSIGN_OR_RETURN(
      auto compiled_model,
      litert::CompiledModel::Create(env, config.model_path, options));
  LITERT_ASSIGN_OR_RETURN(auto input_buffers,
                          compiled_model.CreateInputBuffers(config.signature));
  if (input_buffers.size() != 1) {
    return absl::InvalidArgumentError(
        absl::StrCat("Expected one input, got ", input_buffers.size()));
  }
  LITERT_ASSIGN_OR_RETURN(auto input_type, input_buffers[0].TensorType());
  LITERT_ASSIGN_OR_RETURN(auto input_elements,
                          input_type.Layout().NumElements());
  LITERT_ASSIGN_OR_RETURN(
      auto tokenizer,
      litert::lm::SentencePieceTokenizer::CreateFromFile(
          config.tokenizer_path));

  std::cout << "EMBEDDING_READY" << std::endl;
  std::string text_file;
  while (std::getline(std::cin, text_file)) {
    auto request_start = std::chrono::steady_clock::now();
    auto text = ReadTextFile(text_file);
    if (!text.ok()) {
      std::cout << "EMBEDDING_ERROR:" << text.status() << std::endl;
      continue;
    }
    auto tokens = TokensFromText(*tokenizer, *text, input_elements);
    if (!tokens.ok()) {
      std::cout << "EMBEDDING_ERROR:" << tokens.status() << std::endl;
      continue;
    }
    std::vector<float> output;
    auto run_status = RunEmbedding(compiled_model, config.signature, *tokens,
                                   &output);
    if (!run_status.ok()) {
      std::cout << "EMBEDDING_ERROR:" << run_status << std::endl;
      continue;
    }
    auto valid = ValidateEmbedding(output);
    if (!valid.ok()) {
      std::cout << "EMBEDDING_ERROR:" << valid << std::endl;
      continue;
    }
    const int64_t run_ms =
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - request_start)
            .count();
    std::cout << "EMBEDDING_JSON:"
              << BuildEmbeddingJson(output, run_ms, 0) << std::endl;
  }
  return absl::OkStatus();
}

}  // namespace

int main(int argc, char** argv) {
  auto config = ParseArgs(argc, argv);
  if (!config.ok()) {
    if (absl::IsCancelled(config.status())) {
      return 0;
    }
    std::cerr << config.status() << "\n";
    PrintUsage(argv[0]);
    return 2;
  }
  auto status = config->worker ? RunWorker(*config) : RunProbe(*config);
  if (!status.ok()) {
    std::cerr << "FAILED: " << status << "\n";
    return 1;
  }
  std::cout << "PASS\n";
  return 0;
}
