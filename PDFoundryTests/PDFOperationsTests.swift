import PDFKit
import Testing
import UIKit
@testable import PDFoundry

@MainActor
struct PDFOperationsTests {
    private let folder: URL

    init() throws {
        folder = FileManager.default.temporaryDirectory.appending(path: "PDFoundryTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    private func makePDF(named name: String, pages: Int) throws -> URL {
        let rect = CGRect(x: 0, y: 0, width: 612, height: 792)
        let data = UIGraphicsPDFRenderer(bounds: rect).pdfData { context in
            for index in 0..<pages {
                context.beginPage()
                ("\(name) page \(index + 1)" as NSString).draw(at: CGPoint(x: 72, y: 72), withAttributes: [.font: UIFont.systemFont(ofSize: 24)])
            }
        }
        let url = folder.appending(path: "\(name).pdf")
        try data.write(to: url)
        return url
    }

    @Test func mergeKeepsEveryPageInOrder() throws {
        let first = try makePDF(named: "Alpha", pages: 2)
        let second = try makePDF(named: "Beta", pages: 3)

        let merged = try PDFOperations.merge([first, second])

        #expect(merged.pageCount == 5)
        #expect(merged.page(at: 0)?.string?.contains("Alpha page 1") == true)
        #expect(merged.page(at: 2)?.string?.contains("Beta page 1") == true)
        #expect(merged.page(at: 4)?.string?.contains("Beta page 3") == true)
    }

    @Test func mergeRejectsUnreadableFiles() throws {
        let bogus = folder.appending(path: "Broken.pdf")
        try Data("not a pdf".utf8).write(to: bogus)

        #expect(throws: PDFoundryError.self) {
            _ = try PDFOperations.merge([bogus])
        }
    }

    @Test func extractCopiesOnlyChosenPages() throws {
        let url = try makePDF(named: "Source", pages: 4)
        let document = try #require(PDFDocument(url: url))

        let extracted = try PDFOperations.extract([3, 1], from: document)

        #expect(extracted.pageCount == 2)
        #expect(extracted.page(at: 0)?.string?.contains("page 2") == true)
        #expect(extracted.page(at: 1)?.string?.contains("page 4") == true)
        #expect(document.pageCount == 4)
    }

    @Test func signatureStampSurvivesSavingAndReloading() throws {
        let url = try makePDF(named: "Contract", pages: 1)
        let document = try #require(PDFDocument(url: url))
        let page = try #require(document.page(at: 0))

        let signature = UIGraphicsImageRenderer(size: CGSize(width: 200, height: 80)).image { context in
            UIColor.red.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 200, height: 80))
        }
        // Lower-left quarter of the page, in PDF coordinates (origin bottom-left).
        PDFOperations.stamp(signature, on: page, bounds: CGRect(x: 100, y: 100, width: 200, height: 80))
        #expect(document.write(to: url))

        let reloaded = try #require(PDFDocument(url: url))
        let reloadedPage = try #require(reloaded.page(at: 0))
        #expect(reloadedPage.annotations.contains { $0.type == "Stamp" })

        // Render at 1 point per pixel and sample the middle of the stamp.
        let rendered = reloadedPage.thumbnail(of: CGSize(width: 612, height: 792), for: .mediaBox)
        let pixel = try #require(rendered.pixel(atFraction: CGPoint(x: 200.0 / 612.0, y: (792.0 - 140.0) / 792.0)))
        #expect(pixel.red > 200 && pixel.green < 80 && pixel.blue < 80, "The stamped image should be painted into the saved page")
    }

    @Test func libraryNamesNeverCollideOrEscapeTheFolder() async throws {
        let store = DocumentStore(directory: folder)
        let first = try makePDF(named: "Report", pages: 1)

        #expect(store.uniqueURL(named: "Report").lastPathComponent == "Report 2.pdf")
        #expect(store.uniqueURL(named: "../../etc/passwd").deletingLastPathComponent().standardizedFileURL == folder.standardizedFileURL)
        #expect(DocumentStore.sanitized("  Invoice.pdf ") == "Invoice")

        await store.refresh()
        let item = try #require(store.items.first { $0.url.lastPathComponent == first.lastPathComponent })
        #expect(item.pageCount == 1)

        try await store.duplicate(item)
        #expect(store.items.count == 2)

        let merged = try await store.merge(store.items, named: "Both", deleteOriginals: true)
        #expect(store.items.map(\.url.lastPathComponent) == [merged.lastPathComponent])
        #expect(store.items.first?.pageCount == 2)
    }
}

private extension UIImage {
    /// Reads one pixel; `fraction` is measured from the top-left corner.
    func pixel(atFraction fraction: CGPoint) -> (red: UInt8, green: UInt8, blue: UInt8)? {
        guard let cgImage else { return nil }
        var data = [UInt8](repeating: 0, count: 4)
        guard let context = CGContext(data: &data, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        let x = CGFloat(cgImage.width) * fraction.x
        let y = CGFloat(cgImage.height) * fraction.y
        // Core Graphics counts rows from the bottom; shift the image so the wanted pixel lands at the origin.
        context.draw(cgImage, in: CGRect(x: -x, y: y - CGFloat(cgImage.height), width: CGFloat(cgImage.width), height: CGFloat(cgImage.height)))
        return (data[0], data[1], data[2])
    }
}
