import ImageIO
import PDFKit
import UniformTypeIdentifiers

/// Export as Images: each chosen page as a PNG or JPEG picture, as the reader sees it (crop box and rotation),
/// written to a temporary folder for the share sheet and removed afterwards.
enum ExportImages {
    static let screenDPI: CGFloat = 150
    static let printDPI: CGFloat = 300
    nonisolated static let jpegQuality: CGFloat = 0.9

    /// The file names: "<name> page 1.png", zero-padded to the page count's width when it has more than 9 pages.
    static func fileName(base: String, page index: Int, of count: Int, png: Bool) -> String {
        let width = count > 9 ? String(count).count : 1
        let number = String(index + 1)
        let padded = String(repeating: "0", count: max(0, width - number.count)) + number
        return "\(base) page \(padded).\(png ? "png" : "jpg")"
    }

    /// Renders `pages` (zero-based) of `document` at `dpi`. Each page is drawn on the main actor (PDFKit's document
    /// is not shared across threads) and encoded away from it.
    static func run(_ document: PDFDocument, pages: [Int], png: Bool, dpi: CGFloat, baseName: String) async throws -> [URL] {
        let folder = FileManager.default.temporaryDirectory.appending(path: "Export-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        var written: [URL] = []
        do {
            for index in pages where index >= 0 && index < document.pageCount {
                try Task.checkCancellation()
                guard let page = document.page(at: index), let image = PageImage.image(of: page, dpi: dpi) else { continue }
                let url = folder.appending(path: fileName(base: baseName, page: index, of: document.pageCount, png: png))
                try await encode(image, to: url, png: png, dpi: dpi)
                written.append(url)
            }
        } catch {
            try? FileManager.default.removeItem(at: folder)
            throw error
        }
        guard !written.isEmpty else {
            try? FileManager.default.removeItem(at: folder)
            throw PDFoundryError.nothingToSave
        }
        return written
    }

    @concurrent
    private nonisolated static func encode(_ image: CGImage, to url: URL, png: Bool, dpi: CGFloat) async throws {
        let type = (png ? UTType.png : UTType.jpeg).identifier as CFString
        guard let destination = CGImageDestinationCreateWithURL(url as CFURL, type, 1, nil) else { throw PDFoundryError.writeFailed }
        var properties: [CFString: Any] = [kCGImagePropertyDPIWidth: dpi, kCGImagePropertyDPIHeight: dpi]
        if !png { properties[kCGImageDestinationLossyCompressionQuality] = jpegQuality }
        CGImageDestinationAddImage(destination, image, properties as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { throw PDFoundryError.writeFailed }
    }

    /// Removes the temporary folder of an export once the share sheet is done with it.
    nonisolated static func cleanUp(_ files: [URL]) {
        for folder in Set(files.map { $0.deletingLastPathComponent() }) where folder.lastPathComponent.hasPrefix("Export-") {
            try? FileManager.default.removeItem(at: folder)
        }
    }
}
