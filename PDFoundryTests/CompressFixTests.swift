import PDFKit
import Testing
import UIKit
@testable import PDFoundry

/// Compress never changes how a picture looks beyond JPEG loss: images it can't re-encode exactly are left alone.
@MainActor
struct CompressFixTests {
    private let side = 500

    private func render(_ data: Data) throws -> CGImage {
        let page = try #require(PDFDocument(data: data)?.page(at: 0))
        return try #require(PageImage.image(of: page, dpi: 36))
    }

    /// The result, if any, looks like the source.
    private func expectSameLook(_ source: Data, _ result: Data?) throws {
        guard let result else { return }
        // JPEG changes single pixels of noise a lot; on average the picture must stay the same.
        let a = MarkupFixture.rgba(try render(source)), b = MarkupFixture.rgba(try render(result))
        #expect(a.count == b.count)
        let total = zip(a, b).reduce(0) { $0 + abs(Int($1.0) - Int($1.1)) }
        let mean = Double(total) / Double(max(a.count, 1))
        #expect(mean < 12, "the picture looks different (mean difference \(mean))")
    }

    private func rgbImage(length: String? = nil, extra: String = "") -> Data {
        let pixels = RawPDF.flate(RawPDF.noise(width: side, height: side, components: 3))
        return RawPDF.stream("/Type /XObject /Subtype /Image /Width \(side) /Height \(side) /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode \(extra)",
                             pixels, length: length)
    }

    @Test func plainRGBImageIsCompressed() throws {
        let source = RawPDF.imagePage(rgbImage())
        let result = try Compress.compress(source, password: nil, level: .smaller, name: "x")
        #expect(result != nil)
        try expectSameLook(source, result)
    }

    @Test func indirectLengthIsReadWhole() throws {
        let pixels = RawPDF.flate(RawPDF.noise(width: side, height: side, components: 3))
        let image = RawPDF.stream("/Type /XObject /Subtype /Image /Width \(side) /Height \(side) /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode",
                                  pixels, length: "12 0 R")
        let source = RawPDF.imagePage(image, extra: [12: RawPDF.object("\(pixels.count)")])
        let found = ImageObjects.find(in: source)
        #expect(found.first?.stream.count == pixels.count, "the whole stream, not \(found.first?.stream.count ?? -1) bytes")
        let result = try Compress.compress(source, password: nil, level: .smaller, name: "x")
        #expect(result != nil)
        try expectSameLook(source, result)
    }

    @Test func indexedImageIsLeftAlone() throws {
        let palette = "<FF0000 00FF00 0000FF FFFF00>"
        let pixels = RawPDF.noise(width: side, height: side, components: 1).map { $0 % 4 }
        let image = RawPDF.stream("/Type /XObject /Subtype /Image /Width \(side) /Height \(side) /ColorSpace [/Indexed /DeviceRGB 3 \(palette)] /BitsPerComponent 8 /Filter /FlateDecode",
                                  RawPDF.flate(Data(pixels)))
        let source = RawPDF.imagePage(image)
        #expect(ImageObjects.find(in: source).isEmpty)
        #expect(try Compress.compress(source, password: nil, level: .smallest, name: "x") == nil)
    }

    @Test func separationImageIsLeftAlone() throws {
        let image = RawPDF.stream("/Type /XObject /Subtype /Image /Width \(side) /Height \(side) /ColorSpace [/Separation /Spot /DeviceCMYK << /FunctionType 2 /Domain [0 1] /C0 [0 0 0 0] /C1 [0 0 0 1] /N 1 >>] /BitsPerComponent 8 /Filter /FlateDecode",
                                  RawPDF.flate(RawPDF.noise(width: side, height: side, components: 1)))
        let source = RawPDF.imagePage(image)
        #expect(ImageObjects.find(in: source).isEmpty)
        #expect(try Compress.compress(source, password: nil, level: .smallest, name: "x") == nil)
    }

    @Test func iccBasedRGBIsCompressedButLabIsNot() throws {
        let icc = RawPDF.stream("/N 3 /Alternate /DeviceRGB", Data(repeating: 0, count: 16))
        let rgb = RawPDF.stream("/Type /XObject /Subtype /Image /Width \(side) /Height \(side) /ColorSpace [/ICCBased 9 0 R] /BitsPerComponent 8 /Filter /FlateDecode",
                                RawPDF.flate(RawPDF.noise(width: side, height: side, components: 3)))
        #expect(ImageObjects.find(in: RawPDF.imagePage(rgb, extra: [9: icc])).count == 1)
        let lab = RawPDF.stream("/Type /XObject /Subtype /Image /Width \(side) /Height \(side) /ColorSpace [/Lab << /WhitePoint [0.95 1 1.09] >>] /BitsPerComponent 8 /Filter /FlateDecode",
                                RawPDF.flate(RawPDF.noise(width: side, height: side, components: 3)))
        #expect(ImageObjects.find(in: RawPDF.imagePage(lab)).isEmpty)
        let decode = rgbImage(extra: "/Decode [1 0 1 0 1 0]")
        #expect(ImageObjects.find(in: RawPDF.imagePage(decode)).isEmpty)
    }

    @Test func aStreamThatDoesNotDecodeToItsSizeIsSkipped() throws {
        // Says 500 x 500 but holds pixels for 400 x 400.
        let short = RawPDF.stream("/Type /XObject /Subtype /Image /Width \(side) /Height \(side) /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode",
                                  RawPDF.flate(RawPDF.noise(width: 400, height: 400, components: 3)))
        let found = ImageObjects.find(in: RawPDF.imagePage(short))
        #expect(found.allSatisfy { $0.recompressed(maximumSide: 2000, quality: 0.6) == nil })
    }

    @Test func onlyTheLatestVersionOfAnObjectIsUsed() throws {
        // The first version of object 5 is a large noisy picture; an update replaces it with a small grey one.
        let original = RawPDF.imagePage(rgbImage())
        let grey = RawPDF.stream("/Type /XObject /Subtype /Image /Width 2 /Height 2 /ColorSpace /DeviceGray /BitsPerComponent 8",
                                 Data([128, 128, 128, 128]))
        let updated = RawPDF.update(original, with: [5: grey])
        let found = ImageObjects.find(in: updated)
        #expect(found.allSatisfy { $0.width == 2 }, "the old version is not looked at")
        let result = try Compress.compress(updated, password: nil, level: .smaller, name: "x")
        try expectSameLook(updated, result)
    }
}
