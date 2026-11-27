import CryptoKit
import PDFKit
import Testing
import UIKit
@testable import PDFoundry

/// Page Numbers, Watermark, Compress and Export as Images.
@MainActor
struct PageToolsTests {
    private let folder: URL

    init() throws {
        folder = try MarkupFixture.folder("PageToolsTests")
    }

    private func blank(pages: Int, rotation: Int = 0, named name: String = "Blank.pdf") throws -> URL {
        try MarkupFixture.write(MarkupFixture.textPDF(Array(repeating: "", count: pages), rotation: rotation), named: name, in: folder)
    }

    /// Rows of `image` (0 = top) that have pixels clearly darker than white.
    private func inkedRows(_ image: CGImage) -> [Int] {
        let pixels = MarkupFixture.rgba(image)
        var rows: [Int] = []
        for row in 0..<image.height {
            for column in 0..<image.width {
                let o = (row * image.width + column) * 4
                if Int(pixels[o]) + Int(pixels[o + 1]) + Int(pixels[o + 2]) < 450 { rows.append(row); break }
            }
        }
        return rows
    }

    private func inkedColumns(_ image: CGImage) -> [Int] {
        let pixels = MarkupFixture.rgba(image)
        var columns: [Int] = []
        for column in 0..<image.width {
            for row in 0..<image.height {
                let o = (row * image.width + column) * 4
                if Int(pixels[o]) + Int(pixels[o + 1]) + Int(pixels[o + 2]) < 450 { columns.append(column); break }
            }
        }
        return columns
    }

    // MARK: Page numbers

    @Test func pageNumberLabels() {
        var style = PageNumberStyle()
        #expect(style.position == .bottomCenter && style.format == .number && style.startAt == 1 && !style.skipFirst)
        style.format = .pageNumberOfTotal
        #expect(style.label(forPage: 0, count: 3) == "Page 1 of 3")
        style.format = .numberOfTotal
        #expect(style.label(forPage: 2, count: 3) == "3 of 3")
        style.format = .pageNumber
        style.startAt = 5
        #expect(style.label(forPage: 1, count: 3) == "Page 6")
        #expect(PageNumberStyle.Format.allCases.map(\.example) == ["1", "Page 1", "1 of 12", "Page 1 of 12"])
        #expect(PageNumberStyle.Position.allCases.count == 6)
    }

    @Test func skipFirstStartsOnPageTwo() async throws {
        var style = PageNumberStyle()
        style.skipFirst = true
        style.format = .numberOfTotal
        #expect(style.label(forPage: 0, count: 4) == nil)
        #expect(style.label(forPage: 1, count: 4) == "1 of 3")
        #expect(style.label(forPage: 3, count: 4) == "3 of 3")

        let url = try blank(pages: 3)
        let (model, _) = await MarkupFixture.open(url)
        model.addPageNumbers(style)
        let saved = try #require(PDFDocument(url: url))
        #expect(saved.page(at: 0)?.annotations.isEmpty == true)
        #expect(saved.page(at: 1)?.annotations.count == 1)
        #expect(saved.page(at: 2)?.annotations.count == 1)
    }

    @Test func numbersOnRotatedPageWhereReaderSeesThem() async throws {
        let url = try blank(pages: 3, rotation: 90)
        let (model, view) = await MarkupFixture.open(url)
        var style = PageNumberStyle()
        style.format = .pageNumberOfTotal
        model.addPageNumbers(style)
        #expect(model.canUndo)

        let page = try #require(PDFDocument(url: url)?.page(at: 0))
        #expect(page.rotation == 90)
        let stamp = try #require(page.annotations.first)
        #expect(Annotations.isOurs(stamp) && Annotations.isPageStamp(stamp))
        #expect(!Annotations.isDeletable(stamp) && !Annotations.isMovable(stamp))

        let image = try #require(PageImage.image(of: page, dpi: 72))
        let rows = inkedRows(image), columns = inkedColumns(image)
        let height = Double(image.height), width = Double(image.width)
        #expect(rows.count > 0)
        // In the bottom band of the page as the reader sees it, centred, about 36 pt from the edge.
        #expect(rows.allSatisfy { Double($0) > height * 0.85 }, "rows \(rows.first ?? -1)…\(rows.last ?? -1) of \(height)")
        #expect(height - Double(rows.max() ?? 0) > 30 && height - Double(rows.max() ?? 0) < 42)
        let middle = Double((columns.min() ?? 0) + (columns.max() ?? 0)) / 2
        #expect(abs(middle - width / 2) < width * 0.03)
        // A label reads left to right as shown: wider than tall.
        #expect((columns.max() ?? 0) - (columns.min() ?? 0) > (rows.max() ?? 0) - (rows.min() ?? 0))

        // Not selectable in the reader.
        let shown = view.convert(page.bounds(for: .cropBox), from: page)
        _ = shown
        let livePage = try #require(model.document?.page(at: 0))
        let liveStamp = try #require(livePage.annotations.first)
        let centre = view.convert(liveStamp.bounds, from: livePage)
        model.select(at: CGPoint(x: centre.midX, y: centre.midY))
        #expect(model.selectedAnnotation == nil)

        model.undo()
        #expect(PDFDocument(url: url)?.page(at: 0)?.annotations.isEmpty == true)
    }

    // MARK: Watermark

    @Test func watermarkArabicIsDrawn() async throws {
        let url = try blank(pages: 1)
        let blankPage = try #require(PDFDocument(url: url)?.page(at: 0))
        let before = try #require(PageImage.image(of: blankPage, dpi: 72))
        let (model, _) = await MarkupFixture.open(url)
        model.addWatermark(WatermarkStyle(text: "سري للغاية", opacity: 1, diagonal: false, color: Mark.ink))
        let page = try #require(PDFDocument(url: url)?.page(at: 0))
        let after = try #require(PageImage.image(of: page, dpi: 72))
        let changed = MarkupFixture.difference(before, after)
        #expect(changed > 0.01, "the Arabic text is drawn, not blank (\(changed))")
        let columns = inkedColumns(after)
        let span = Double((columns.max() ?? 0) - (columns.min() ?? 0)) / Double(after.width)
        #expect(span > 0.5 && span < 0.7, "about 60% of the page width (\(span))")
    }

    @Test func watermarkDefaultsAndDiagonal() async throws {
        let style = WatermarkStyle()
        #expect(style.text == "CONFIDENTIAL" && style.opacity == 0.25 && style.diagonal)
        #expect(WatermarkStyle.quickTexts == ["CONFIDENTIAL", "DRAFT", "COPY"])
        let url = try blank(pages: 2)
        let (model, _) = await MarkupFixture.open(url)
        model.addWatermark(WatermarkStyle(text: "DRAFT", opacity: 1, diagonal: true, color: Mark.ink))
        let page = try #require(PDFDocument(url: url)?.page(at: 1))
        #expect(page.annotations.count == 1, "on every page")
        let image = try #require(PageImage.image(of: page, dpi: 72))
        let rows = inkedRows(image), columns = inkedColumns(image)
        // Diagonal: it spans a good part of the height too.
        #expect(Double((rows.max() ?? 0) - (rows.min() ?? 0)) > Double(image.height) * 0.3)
        #expect(Double((columns.max() ?? 0) - (columns.min() ?? 0)) > Double(image.width) * 0.4)
    }

    @Test func watermarkAndNumbersKeepPassword() async throws {
        let url = folder.appending(path: "Locked.pdf")
        let plain = try #require(PDFDocument(data: MarkupFixture.textPDF(["One", "Two"])))
        #expect(plain.write(to: url, withOptions: PDFOperations.writeOptions(password: "pw-9")))
        let (model, _) = await MarkupFixture.open(url, password: "pw-9")
        model.addPageNumbers(PageNumberStyle())
        model.addWatermark(WatermarkStyle())
        let saved = try #require(PDFDocument(url: url))
        #expect(saved.isLocked)
        #expect(saved.unlock(withPassword: "pw-9"))
        #expect(saved.page(at: 1)?.annotations.count == 2)
    }

    // MARK: Compress

    /// A page with a large, noisy picture (it compresses badly losslessly) and a line of text.
    private func photoPDF(named name: String = "Photo.pdf") throws -> URL {
        let side = 1600
        var bytes = [UInt8](repeating: 0, count: side * side * 4)
        var seed: UInt32 = 12345
        for index in stride(from: 0, to: bytes.count, by: 4) {
            seed = seed &* 1_103_515_245 &+ 12345
            let base = UInt8((index / 4 / side) * 200 / side)
            bytes[index] = base &+ UInt8(seed >> 24 & 0x1F)
            bytes[index + 1] = UInt8(seed >> 16 & 0xFF) / 2 &+ 60
            bytes[index + 2] = 140 &+ UInt8(seed >> 8 & 0x3F)
            bytes[index + 3] = 255
        }
        let provider = CGDataProvider(data: Data(bytes) as CFData)!
        let photo = CGImage(width: side, height: side, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: side * 4,
                            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                            provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)!
        let data = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792)).pdfData { context in
            context.beginPage()
            UIImage(cgImage: photo).draw(in: CGRect(x: 56, y: 150, width: 500, height: 500))
            ("Holiday photos" as NSString).draw(at: CGPoint(x: 56, y: 80), withAttributes: [.font: UIFont.systemFont(ofSize: 24)])
        }
        return try MarkupFixture.write(data, named: name, in: folder)
    }

    @Test func compressSmallerKeepsTextSourceUnchanged() async throws {
        let url = try photoPDF()
        let original = try Data(contentsOf: url)
        let digest = SHA256.hash(data: original)
        // PDFKit's own image options make no difference here, which is why Compress re-encodes the pictures itself.
        let document = try #require(PDFDocument(data: original))
        let withOptions = document.dataRepresentation(options: [PDFDocumentWriteOption.saveImagesAsJPEGOption: true,
                                                                PDFDocumentWriteOption.optimizeImagesForScreenOption: true])
        #expect(Double(withOptions?.count ?? 0) > Double(original.count) * 0.95)

        let result = try #require(try await Compress.run(url, password: nil))
        #expect(Double(result.count) < Double(original.count) * 0.95, "\(result.count) vs \(original.count)")
        let compressed = try #require(PDFDocument(data: result))
        #expect(compressed.string?.contains("Holiday photos") == true, "text stays text")
        #expect(SHA256.hash(data: try Data(contentsOf: url)) == digest, "the source is not touched")
        // The picture is still there and looks the same at a glance.
        let firstBefore = try #require(document.page(at: 0)), firstAfter = try #require(compressed.page(at: 0))
        let before = try #require(PageImage.image(of: firstBefore, dpi: 36))
        let after = try #require(PageImage.image(of: firstAfter, dpi: 36))
        #expect(MarkupFixture.difference(before, after) < 0.05)

        let smallest = try #require(try await Compress.run(url, password: nil, level: .smallest))
        #expect(smallest.count < result.count)
    }

    @Test func compressNoGainReturnsNil() async throws {
        let url = try MarkupFixture.write(MarkupFixture.textPDF(["Only words here"]), named: "Words.pdf", in: folder)
        #expect(try await Compress.run(url, password: nil) == nil)
        #expect(Compress.noGainMessage == "This file is already as small as it can be.")
    }

    @Test func compressKeepsPassword() async throws {
        let source = try photoPDF(named: "Source.pdf")
        let url = folder.appending(path: "Protected.pdf")
        let document = try #require(PDFDocument(url: source))
        #expect(document.write(to: url, withOptions: PDFOperations.writeOptions(password: "zip-it")))

        await #expect(throws: PDFoundryError.self) { try await Compress.run(url, password: nil) }
        let result = try #require(try await Compress.run(url, password: "zip-it"))
        let compressed = try #require(PDFDocument(data: result))
        #expect(compressed.isEncrypted && compressed.isLocked)
        #expect(compressed.unlock(withPassword: "zip-it"))
    }

    @Test func compressedCopyIsSavedInTheLibrary() async throws {
        let library = folder.appending(path: "Library", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: library, withIntermediateDirectories: true)
        let store = DocumentStore(directory: library, linksFile: folder.appending(path: "opened.json"), copyRoots: [])
        let item = try await store.saveCompressed(Data(MarkupFixture.textPDF(["x"])), named: "Holiday")
        #expect(item.name == "Holiday (compressed)")
        #expect(FileManager.default.fileExists(atPath: library.appending(path: "Holiday (compressed).pdf").path))
    }

    // MARK: Export as images

    @Test func exportNamesAndCount() async throws {
        let url = try blank(pages: 12)
        let document = try #require(PDFDocument(url: url))
        let files = try await ExportImages.run(document, pages: Array(0..<12), png: true, dpi: 300, baseName: "Report")
        defer { ExportImages.cleanUp(files) }
        #expect(files.map(\.lastPathComponent) == (1...12).map { String(format: "Report page %02d.png", $0) })
        let image = try #require(UIImage(contentsOfFile: files[0].path)?.cgImage)
        #expect(image.width == 2550 && image.height == 3300)

        let some = try await ExportImages.run(document, pages: [1, 4], png: false, dpi: 150, baseName: "Report")
        defer { ExportImages.cleanUp(some) }
        #expect(some.map(\.lastPathComponent) == ["Report page 02.jpg", "Report page 05.jpg"])
        let jpeg = try #require(UIImage(contentsOfFile: some[0].path)?.cgImage)
        #expect(jpeg.width == 1275 && jpeg.height == 1650)
    }

    @Test func exportOfAShortFileIsNotPadded() async throws {
        let url = try blank(pages: 3, rotation: 90)
        let document = try #require(PDFDocument(url: url))
        let files = try await ExportImages.run(document, pages: [0], png: true, dpi: 150, baseName: "Short")
        defer { ExportImages.cleanUp(files) }
        #expect(files.map(\.lastPathComponent) == ["Short page 1.png"])
        let image = try #require(UIImage(contentsOfFile: files[0].path)?.cgImage)
        #expect(image.width == 1650 && image.height == 1275, "as the reader sees it: turned")
    }
}
