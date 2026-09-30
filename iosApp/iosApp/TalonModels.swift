import Foundation
import ComposeApp
import llama
#if canImport(FoundationModels)
import FoundationModels
#endif

/// The orrery triage's models on iOS, handed to
/// `MainViewController(rtc:models:)`. Apple's system model where the
/// phone has it; llama.cpp over a GGUF file everywhere the app runs.
final class TalonModelFactory: NSObject, NativeModelFactory {
    func systemModelUnavailableReason() -> String? {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            switch SystemLanguageModel.default.availability {
            case .available: return nil
            case .unavailable(let reason):
                switch reason {
                case .deviceNotEligible: return "This iPhone cannot run Apple's on-device model."
                case .appleIntelligenceNotEnabled: return "Apple Intelligence is off in Settings."
                case .modelNotReady: return "Apple's on-device model is still downloading."
                @unknown default: return "Apple's on-device model is not available."
                }
            }
        }
        #endif
        return "Apple's on-device model needs iOS 26."
    }

    func openSystemModel() -> NativeModel? {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *), systemModelUnavailableReason() == nil {
            return SystemNativeModel()
        }
        #endif
        return nil
    }

    func openLlama(path: String) -> NativeModel? {
        return LlamaNativeModel(path: path)
    }

    func download(url: String, toPath: String, progress: @escaping (KotlinFloat) -> Void, done: @escaping (String?) -> Void) {
        guard let src = URL(string: url) else { done("bad url"); return }
        let dest = URL(fileURLWithPath: toPath)
        try? FileManager.default.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
        let delegate = DownloadDelegate(dest: dest, progress: progress, done: done)
        let session = URLSession(configuration: .default, delegate: delegate, delegateQueue: nil)
        delegate.session = session
        session.downloadTask(with: src).resume()
    }
}

private final class DownloadDelegate: NSObject, URLSessionDownloadDelegate {
    let dest: URL
    let progress: (KotlinFloat) -> Void
    let done: (String?) -> Void
    var session: URLSession?
    init(dest: URL, progress: @escaping (KotlinFloat) -> Void, done: @escaping (String?) -> Void) {
        self.dest = dest; self.progress = progress; self.done = done
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        if totalBytesExpectedToWrite > 0 {
            progress(KotlinFloat(float: Float(totalBytesWritten) / Float(totalBytesExpectedToWrite)))
        }
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        do {
            if FileManager.default.fileExists(atPath: dest.path) { try FileManager.default.removeItem(at: dest) }
            try FileManager.default.moveItem(at: location, to: dest)
            done(nil)
        } catch {
            done("could not place the model file: \(error.localizedDescription)")
        }
        self.session?.finishTasksAndInvalidate()
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error { done(error.localizedDescription); self.session?.finishTasksAndInvalidate() }
    }
}

#if canImport(FoundationModels)
/// Apple's model. No grammar over this API; instructions ask for JSON
/// and the Kotlin side holds it to the shape.
@available(iOS 26.0, *)
private final class SystemNativeModel: NSObject, NativeModel {
    var name: String { "Apple's on-device model" }

    func complete(system: String, user: String, grammar: String?, maxTokens: Int32) -> String {
        let session = LanguageModelSession(instructions: system)
        var answer = ""
        let gate = DispatchSemaphore(value: 0)
        Task {
            defer { gate.signal() }
            do {
                let response = try await session.respond(to: user, options: GenerationOptions(temperature: 0, maximumResponseTokens: Int(maxTokens)))
                answer = response.content
            } catch {
                answer = ""
            }
        }
        gate.wait()
        return answer
    }

    func close() {}
}
#endif

/// llama.cpp over a GGUF file: Qwen's ChatML, a grammar-bounded greedy
/// sample, the prompt decoded in batches. Adapted from the project's
/// own Swift example.
private final class LlamaNativeModel: NSObject, NativeModel {
    var name: String { "Qwen2.5 1.5B on this phone" }
    private let model: OpaquePointer
    private let context: OpaquePointer
    private let vocab: OpaquePointer
    private let nCtx: Int32 = 4096
    private let batchSize: Int32 = 512

    init?(path: String) {
        llama_backend_init()
        var mparams = llama_model_default_params()
        #if targetEnvironment(simulator)
        mparams.n_gpu_layers = 0
        #endif
        guard let m = llama_model_load_from_file(path, mparams) else { return nil }
        var cparams = llama_context_default_params()
        cparams.n_ctx = UInt32(nCtx)
        cparams.n_batch = UInt32(batchSize)
        let threads = Int32(max(1, min(6, ProcessInfo.processInfo.processorCount - 2)))
        cparams.n_threads = threads
        cparams.n_threads_batch = threads
        guard let c = llama_init_from_model(m, cparams) else { llama_model_free(m); return nil }
        model = m; context = c; vocab = llama_model_get_vocab(m)
        super.init()
    }

    func close() {
        llama_free(context)
        llama_model_free(model)
    }

    func complete(system: String, user: String, grammar: String?, maxTokens: Int32) -> String {
        let prompt = "<|im_start|>system\n\(system)<|im_end|>\n<|im_start|>user\n\(user)<|im_end|>\n<|im_start|>assistant\n"
        llama_memory_clear(llama_get_memory(context), true)
        var tokens = tokenize(prompt, addBos: true)
        if tokens.count + Int(maxTokens) > Int(nCtx) {
            tokens = Array(tokens.suffix(Int(nCtx) - Int(maxTokens)))
        }
        var batch = llama_batch_init(batchSize, 0, 1)
        defer { llama_batch_free(batch) }
        // The prompt, a batch at a time; logits only for the last token.
        var pos: Int32 = 0
        var i = 0
        while i < tokens.count {
            batch.n_tokens = 0
            let end = min(i + Int(batchSize), tokens.count)
            for j in i..<end {
                add(&batch, tokens[j], pos, logits: j == tokens.count - 1)
                pos += 1
            }
            if llama_decode(context, batch) != 0 { return "" }
            i = end
        }
        let chain = llama_sampler_chain_init(llama_sampler_chain_default_params())
        defer { llama_sampler_free(chain) }
        if let grammar, let g = llama_sampler_init_grammar(vocab, grammar, "root") {
            llama_sampler_chain_add(chain, g)
        }
        llama_sampler_chain_add(chain, llama_sampler_init_greedy())
        var out: [CChar] = []
        var produced: Int32 = 0
        while produced < maxTokens {
            let id = llama_sampler_sample(chain, context, batch.n_tokens - 1)
            if llama_vocab_is_eog(vocab, id) { break }
            out.append(contentsOf: piece(id))
            batch.n_tokens = 0
            add(&batch, id, pos, logits: true)
            pos += 1
            produced += 1
            if llama_decode(context, batch) != 0 { break }
        }
        let text = String(decoding: out.map { UInt8(bitPattern: $0) }, as: UTF8.self)
        return text.components(separatedBy: "<|im_end|>").first ?? text
    }

    private func add(_ batch: inout llama_batch, _ id: llama_token, _ pos: llama_pos, logits: Bool) {
        let n = Int(batch.n_tokens)
        batch.token[n] = id
        batch.pos[n] = pos
        batch.n_seq_id[n] = 1
        batch.seq_id[n]![0] = 0
        batch.logits[n] = logits ? 1 : 0
        batch.n_tokens += 1
    }

    private func tokenize(_ text: String, addBos: Bool) -> [llama_token] {
        let n = text.utf8.count + (addBos ? 1 : 0) + 1
        let buf = UnsafeMutablePointer<llama_token>.allocate(capacity: n)
        defer { buf.deallocate() }
        let count = llama_tokenize(vocab, text, Int32(text.utf8.count), buf, Int32(n), addBos, true)
        if count < 0 { return [] }
        return Array(UnsafeBufferPointer(start: buf, count: Int(count)))
    }

    private func piece(_ token: llama_token) -> [CChar] {
        var buf = [CChar](repeating: 0, count: 16)
        let n = llama_token_to_piece(vocab, token, &buf, Int32(buf.count), 0, false)
        if n >= 0 { return Array(buf.prefix(Int(n))) }
        var big = [CChar](repeating: 0, count: Int(-n))
        let m = llama_token_to_piece(vocab, token, &big, Int32(big.count), 0, false)
        return Array(big.prefix(Int(max(0, m))))
    }
}
