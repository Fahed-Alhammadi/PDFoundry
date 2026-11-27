import Foundation
import PDFKit
import PencilKit
import UIKit
@testable import PDFoundry

/// Helpers shared by the markup and page-tool tests: hand-written PDFs whose text is written as plain strings (so
/// the tests can look for it in the file's bytes), a reader model in a real PDFView, and pixel checks.
@MainActor
enum MarkupFixture {
    /// A PDF with one page per entry of `pages`, each showing its text in Helvetica at the top left.
    /// `rotation` and `crop` apply to every page.
    static func textPDF(_ pages: [String], rotation: Int = 0, crop: CGRect? = nil, size: CGSize = CGSize(width: 612, height: 792)) -> Data {
        // 1 catalog, 2 pages, 3 font, then per page: page object, content stream.
        var objects: [String] = []
        let kids = pages.indices.map { "\(4 + $0 * 2) 0 R" }.joined(separator: " ")
        objects.append("<< /Type /Catalog /Pages 2 0 R >>")
        objects.append("<< /Type /Pages /Kids [\(kids)] /Count \(pages.count) >>")
        objects.append("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        for (index, text) in pages.enumerated() {
            let cropEntry = crop.map { " /CropBox [\($0.minX) \($0.minY) \($0.maxX) \($0.maxY)]" } ?? ""
            objects.append("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 \(size.width) \(size.height)]\(cropEntry) /Rotate \(rotation) /Contents \(5 + index * 2) 0 R /Resources << /Font << /F1 3 0 R >> >> >>")
            let content = "BT /F1 24 Tf 90 \(size.height - 120) Td (\(text)) Tj ET"
            objects.append("<< /Length \(content.utf8.count) >>\nstream\n\(content)\nendstream")
        }
        return Data(FormFixture.assemble(objects).utf8)
    }

    static func write(_ data: Data, named name: String, in folder: URL) throws -> URL {
        let url = folder.appending(path: name)
        try data.write(to: url)
        return url
    }

    static func folder(_ name: String) throws -> URL {
        let folder = FileManager.default.temporaryDirectory.appending(path: "\(name)-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    /// A model showing the file in a real PDFView, as the reader does.
    static func open(_ url: URL, password: String? = nil) async -> (ReaderModel, PDFView) {
        let model = ReaderModel(url: url)
        await model.load()
        if let password { _ = model.unlock(with: password) }
        let view = PDFView(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.document = model.document
        view.layoutIfNeeded()
        model.pdfView = view
        return (model, view)
    }

    static var signature: UIImage {
        UIGraphicsImageRenderer(size: CGSize(width: 200, height: 80)).image { context in
            UIColor.black.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 200, height: 80))
        }
    }

    /// Places `image` through the model, as the user does, at `rect` in the view.
    static func place(_ image: UIImage, in model: ReaderModel, at rect: CGRect) {
        model.beginPlacing(signature: image)
        model.placement?.rect = rect
        model.commitPlacement()
    }

    /// Selects the text `word` on `page` in the view, as a long press does.
    static func select(_ word: String, on page: PDFPage, in view: PDFView, model: ReaderModel) {
        let text = page.string ?? ""
        guard let range = text.range(of: word) else { return }
        view.currentSelection = page.selection(for: NSRange(range, in: text))
        model.hasSelection = true
    }

    /// The centre of the dark pixels of `image` as a fraction of its size (origin top-left), or nil if none.
    static func darkCentre(_ image: CGImage, threshold: UInt8 = 90) -> CGPoint? {
        let pixels = rgba(image)
        let width = image.width, height = image.height
        var sumX = 0.0, sumY = 0.0, count = 0.0
        for row in 0..<height {
            for column in 0..<width {
                let offset = (row * width + column) * 4
                if pixels[offset] < threshold, pixels[offset + 1] < threshold, pixels[offset + 2] < threshold {
                    sumX += Double(column); sumY += Double(row); count += 1
                }
            }
        }
        guard count > 0 else { return nil }
        return CGPoint(x: sumX / count / Double(width), y: sumY / count / Double(height))
    }

    /// How many pixels of `image` are dark.
    static func darkCount(_ image: CGImage, threshold: UInt8 = 120) -> Int {
        let pixels = rgba(image)
        var count = 0
        for index in stride(from: 0, to: pixels.count, by: 4)
            where pixels[index] < threshold && pixels[index + 1] < threshold && pixels[index + 2] < threshold { count += 1 }
        return count
    }

    /// The share of pixels that differ by more than a little between two images of the same size (0…1).
    static func difference(_ a: CGImage, _ b: CGImage) -> Double {
        guard a.width == b.width, a.height == b.height else { return 1 }
        let pa = rgba(a), pb = rgba(b)
        var differing = 0
        for index in stride(from: 0, to: pa.count, by: 4) {
            let delta = max(abs(Int(pa[index]) - Int(pb[index])), abs(Int(pa[index + 1]) - Int(pb[index + 1])),
                            abs(Int(pa[index + 2]) - Int(pb[index + 2])))
            if delta > 40 { differing += 1 }
        }
        return Double(differing) / Double(a.width * a.height)
    }

    static func rgba(_ image: CGImage) -> [UInt8] {
        let width = image.width, height = image.height
        var data = [UInt8](repeating: 0, count: width * height * 4)
        data.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8,
                                          bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return }
            context.setFillColor(UIColor.white.cgColor)
            context.fill(CGRect(x: 0, y: 0, width: width, height: height))
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        return data
    }

    /// Where a point in page space shows on the displayed page (crop box, rotation), as a fraction from the top left.
    static func displayedFraction(of point: CGPoint, on page: PDFPage) -> CGPoint {
        let shown = page.bounds(for: .cropBox).applying(page.transform(for: .cropBox))
        let mapped = point.applying(page.transform(for: .cropBox))
        return CGPoint(x: (mapped.x - shown.minX) / shown.width, y: (shown.maxY - mapped.y) / shown.height)
    }

    /// A short PencilKit stroke, for drawings.
    static func stroke(from start: CGPoint, to end: CGPoint) -> PKStroke {
        let points = (0...10).map { step -> PKStrokePoint in
            let t = CGFloat(step) / 10
            return PKStrokePoint(location: CGPoint(x: start.x + (end.x - start.x) * t, y: start.y + (end.y - start.y) * t),
                                 timeOffset: TimeInterval(step) * 0.01, size: CGSize(width: 4, height: 4),
                                 opacity: 1, force: 1, azimuth: 0, altitude: .pi / 2)
        }
        return PKStroke(ink: PKInk(.pen, color: .black), path: PKStrokePath(controlPoints: points, creationDate: .now))
    }
}
