import Observation
import UIKit

struct SavedSignature: Identifiable {
    let url: URL
    let image: UIImage

    var id: URL { url }
}

/// What a saved drawing is: a signature or initials. Each kind has its own list and folder.
enum SignatureKind {
    case signature, initials

    var folder: String {
        switch self {
        case .signature: "Signatures"
        case .initials: "Initials"
        }
    }
}

/// Signatures (or initials) the user has drawn, kept as transparent PNGs inside the app's private storage.
@Observable
final class SignatureStore {
    private(set) var signatures: [SavedSignature] = []
    let kind: SignatureKind
    private let directory: URL

    init(kind: SignatureKind = .signature, root: URL = URL.applicationSupportDirectory) {
        self.kind = kind
        self.directory = root.appending(path: kind.folder)
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
