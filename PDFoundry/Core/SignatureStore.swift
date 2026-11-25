import Observation
import UIKit

struct SavedSignature: Identifiable {
    let url: URL
    let image: UIImage

    var id: URL { url }
}

/// Signatures the user has drawn, kept as transparent PNGs inside the app's private storage.
@Observable
final class SignatureStore {
    private(set) var signatures: [SavedSignature] = []
    private let directory: URL

    init(directory: URL = URL.applicationSupportDirectory.appending(path: "Signatures")) {
        self.directory = directory
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        load()
    }

    func add(_ image: UIImage) {
        guard let data = image.pngData() else { return }
        let url = directory.appending(path: "\(Int(Date.now.timeIntervalSince1970 * 1000)).png")
        do {
            try data.write(to: url, options: [.atomic, .completeFileProtection])
            load()
        } catch {
            // The signature can still be placed once; it just is not kept.
        }
    }

    func delete(_ signature: SavedSignature) {
        try? FileManager.default.removeItem(at: signature.url)
        load()
    }

    private func load() {
        let urls = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        signatures = urls
            .filter { $0.pathExtension == "png" }
            .sorted { $0.lastPathComponent > $1.lastPathComponent }
            .compactMap { url in
                guard let data = try? Data(contentsOf: url), let image = UIImage(data: data, scale: 3) else { return nil }
                return SavedSignature(url: url, image: image)
            }
    }
}
