import PDFKit
import Testing
import UIKit
@testable import PDFoundry

/// Fill & Sign's Initials, Date, Check and Cross.
@MainActor
struct FillSignMarksTests {
    private let folder: URL

    init() throws {
        folder = FileManager.default.temporaryDirectory.appending(path: "FillSignMarksTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    private func makePDF() throws -> URL {
        let data = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792)).pdfData { $0.beginPage() }
        let url = folder.appending(path: "Form.pdf")
        try data.write(to: url)
        return url
    }

    @Test func todayTextUsesMediumStyle() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let date = try #require(calendar.date(from: DateComponents(year: 2026, month: 11, day: 27, hour: 12)))
        #expect(ReaderModel.todayText(date, locale: Locale(identifier: "en_US")) == "Nov 27, 2026")
        #expect(ReaderModel.todayText(date, locale: Locale(identifier: "en_GB")) == "27 Nov 2026")
    }

    @Test func initialsAreStoredApartFromSignatures() {
        let signatures = SignatureStore(kind: .signature, root: folder)
        let initials = SignatureStore(kind: .initials, root: folder)
        let ink = UIGraphicsImageRenderer(size: CGSize(width: 60, height: 30)).image { context in
            UIColor.black.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 60, height: 30))
        }

        initials.add(ink)

        #expect(initials.signatures.count == 1)
        #expect(signatures.signatures.isEmpty)
        #expect(SignatureStore(kind: .signature, root: folder).signatures.isEmpty, "a fresh signature list doesn't see initials")
        #expect(FileManager.default.fileExists(atPath: folder.appending(path: "Initials").path))
        #expect(FileManager.default.fileExists(atPath: folder.appending(path: "Signatures").path))
    }

    @Test func marksAreDarkCrispImages() throws {
        for mark in [Mark.check, .cross] {
            let image = mark.image()
            #expect(max(image.size.width * image.scale, image.size.height * image.scale) >= 512)
            #expect(image.size.width > 0 && image.size.height > 0)
        }
    }

    @Test func checkStampSurvivesSaveOnRotatedPage() throws {
        let url = try makePDF()
        let document = try #require(PDFDocument(url: url))
        let page = try #require(document.page(at: 0))
        page.rotation = 90
        let bounds = CGRect(x: 200, y: 300, width: 40, height: 40)

        #expect(PDFOperations.stamp(Mark.check.image(), on: page, bounds: bounds) != nil)
        #expect(document.write(to: url))

        let reloaded = try #require(PDFDocument(url: url)?.page(at: 0))
        #expect(reloaded.rotation == 90)
        let stamps = reloaded.annotations.filter { $0.type == "Stamp" }
        #expect(stamps.count == 1)
        let saved = try #require(stamps.first).bounds
        #expect(abs(saved.minX - bounds.minX) < 1 && abs(saved.minY - bounds.minY) < 1)
        #expect(abs(saved.width - bounds.width) < 1 && abs(saved.height - bounds.height) < 1)

        // The mark is drawn: the centre of the stamp is dark on the rendered page.
        let rendered = reloaded.thumbnail(of: CGSize(width: 792, height: 612), for: .mediaBox)
        #expect(rendered.hasDarkPixel(inPageRect: bounds, page: reloaded))
    }

    @Test func fillSignStaysAfterPlacingDateAndMarks() async throws {
        let model = ReaderModel(url: try makePDF())
        await model.load()
        let view = PDFView(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        view.autoScales = true
        view.document = model.document
        view.layoutIfNeeded()
        model.pdfView = view
        model.toolMode = .fillSign

        model.beginPlacingDate()
        guard case .text(let text) = model.placement?.content else {
            Issue.record("the date is placed as text")
            return
        }
        #expect(text == ReaderModel.todayText())
        model.placement?.rect = CGRect(x: 100, y: 200, width: 160, height: 40)
        model.commitPlacement()
        #expect(model.mode == .reading && model.toolMode == .fillSign)

        for (index, mark) in [Mark.check, .cross].enumerated() {
            model.beginPlacing(mark: mark)
            #expect(model.mode == .placing)
            model.placement?.rect = CGRect(x: 100, y: 300 + CGFloat(index) * 80, width: 40, height: 40)
            model.commitPlacement()
            #expect(model.toolMode == .fillSign)
        }
        let annotations = view.document?.page(at: 0)?.annotations ?? []
        #expect(annotations.filter { $0.type == "FreeText" }.count == 1)
        #expect(annotations.filter { $0.type == "Stamp" }.count == 2)
    }
}

extension UIImage {
    /// Whether any pixel inside `rect` (page space of an unrotated media box drawn into this image) is dark.
    /// Works for the rotated page too, by mapping through the page's own display transform.
    func hasDarkPixel(inPageRect rect: CGRect, page: PDFPage) -> Bool {
        guard let cgImage else { return false }
        let width = cgImage.width, height = cgImage.height
        var data = [UInt8](repeating: 0, count: width * height * 4)
        guard let context = CGContext(data: &data, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
        // Page space to image pixels (origin top-left), including the page's rotation.
        let box = page.bounds(for: .mediaBox)
        let transform = page.transform(for: .mediaBox)
        let display = rect.applying(transform)
        let shown = box.applying(transform)
        let sx = CGFloat(width) / shown.width, sy = CGFloat(height) / shown.height
        let minX = Int((display.minX - shown.minX) * sx), maxX = Int((display.maxX - shown.minX) * sx)
        let minRow = Int((shown.maxY - display.maxY) * sy), maxRow = Int((shown.maxY - display.minY) * sy)
        for row in max(0, minRow)..<min(height, maxRow) {
            for column in max(0, minX)..<min(width, maxX) {
                let offset = (row * width + column) * 4
                if data[offset] < 90 && data[offset + 1] < 90 && data[offset + 2] < 90 && data[offset + 3] > 200 { return true }
            }
        }
        return false
    }
}
