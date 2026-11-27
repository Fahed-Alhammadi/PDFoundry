import Foundation
import ImageIO
import PDFKit
import UniformTypeIdentifiers

/// Compress: writes a smaller copy of a PDF by re-encoding its pictures as JPEG at a lower resolution. Text, vector
/// drawings, links, form fields and marks stay as they are. The source file is only read.
///
/// PDFKit's own image options (`saveImagesAsJPEG`, `optimizeImagesForScreen`) change nothing on iOS 26/27 (checked
/// in `PageToolsTests`: same size with and without them), so the pictures are re-encoded here: each image object of
/// the file is decoded, scaled down and saved as JPEG, written over the old one as an update at the end of the file,
/// and PDFKit then writes the whole file again (so the old pictures are left out).
nonisolated enum Compress {
    static let noGainMessage = "This file is already as small as it can be."
    /// A result is kept only when it is at least this much smaller.
    static let minimumGain = 0.05

    enum Level: CaseIterable, Hashable {
        case smaller, smallest

        var title: String {
            switch self {
            case .smaller: "Smaller"
            case .smallest: "Smallest"
            }
        }

        var detail: String {
            switch self {
            case .smaller: "Pictures at 150 dpi. Good for reading on screen and printing at home."
            case .smallest: "Pictures at 100 dpi. The smallest file, for sending."
            }
        }

        var dpi: CGFloat { self == .smaller ? 150 : 100 }
        var quality: CGFloat { self == .smaller ? 0.6 : 0.4 }
    }

    /// The compressed file's bytes, or nil when it would not be at least 5% smaller. A protected file needs its
    /// password (full access), and the result keeps it.
    @concurrent
    static func run(_ url: URL, password: String?, level: Level = .smaller) async throws -> Data? {
        let name = url.deletingPathExtension().lastPathComponent
        guard let data = await DocumentStore.readCoordinated(url) else { throw PDFoundryError.unreadable(name) }
        return try compress(data, password: password, level: level, name: name)
    }

    /// The same, for a file already read into memory.
    @concurrent
    static func run(data: Data, password: String?, level: Level, name: String) async throws -> Data? {
        try compress(data, password: password, level: level, name: name)
    }

    static func compress(_ data: Data, password: String?, level: Level, name: String) throws -> Data? {
        let source = try PDFOperations.fullAccess(data, password: password, name: name)
        let keep = source.isEncrypted ? password : nil
        // Image streams of an encrypted file are encrypted: work on a plain copy, and protect the result again.
        let plain: Data
        if source.isEncrypted {
            guard let copy = PDFOperations.unencrypted(source).dataRepresentation() else { throw PDFoundryError.writeFailed }
            plain = copy
        } else {
            plain = data
        }
        var longest: CGFloat = 0
        for index in 0..<source.pageCount {
            guard let page = source.page(at: index) else { continue }
            let box = page.bounds(for: .cropBox)
            longest = max(longest, box.width, box.height)
        }
        // An image never needs more pixels than cover the largest page at the level's resolution.
        let maximumSide = max(64, longest / 72 * level.dpi)

        let replacements = ImageObjects.find(in: plain).compactMap { image in
            image.recompressed(maximumSide: maximumSide, quality: level.quality)
        }
        guard !replacements.isEmpty else { return nil }
        guard let updated = ImageObjects.update(plain, replacing: replacements),
              let document = PDFDocument(data: updated), document.pageCount == source.pageCount,
              let result = document.dataRepresentation(options: PDFOperations.writeOptions(password: keep)) else { return nil }
        guard Double(result.count) <= Double(data.count) * (1 - minimumGain) else { return nil }
        return result
    }
}
