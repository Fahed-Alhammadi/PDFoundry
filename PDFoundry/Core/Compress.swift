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

/// The image objects of a PDF file, found in its bytes, and how to write new versions of them.
nonisolated struct ImageObjects {
    let number: Int
    let generation: Int
    let width: Int
    let height: Int
    let filter: String
    let predictor: Int
    let colors: Int
    let smask: String?
    let stream: Data

    /// Every image object the app knows how to re-encode: 8 bits per component, gray or RGB, Flate (with or without
    /// a PNG predictor) or JPEG, no colour-key mask or decode array. Soft masks themselves are left alone, so
    /// transparency stays exactly as it was (the image keeps its `/SMask`).
    static func find(in data: Data) -> [ImageObjects] {
        guard let text = String(data: data, encoding: .isoLatin1) as NSString? else { return [] }
        let header = try! NSRegularExpression(pattern: #"(\d+)\s+(\d+)\s+obj\b"#)
        var found: [ImageObjects] = []
        var masks: Set<String> = []
        for match in header.matches(in: text as String, range: NSRange(location: 0, length: text.length)) {
            let bodyStart = match.range.location + match.range.length
            let end = text.range(of: "endobj", range: NSRange(location: bodyStart, length: text.length - bodyStart))
            guard end.location != NSNotFound else { continue }
            let streamKeyword = text.range(of: "stream", range: NSRange(location: bodyStart, length: end.location - bodyStart))
            guard streamKeyword.location != NSNotFound else { continue }
            let dictionary = text.substring(with: NSRange(location: bodyStart, length: streamKeyword.location - bodyStart))
            if let mask = value(of: "SMask", in: dictionary, pattern: #"(\d+\s+\d+\s+R)"#) { masks.insert(mask) }
            guard dictionary.range(of: #"/Subtype\s*/Image"#, options: .regularExpression) != nil else { continue }
            guard dictionary.range(of: "/ImageMask true") == nil, dictionary.range(of: "/Mask") == nil,
                  dictionary.range(of: "/Decode ") == nil && dictionary.range(of: "/Decode[") == nil,
                  value(of: "BitsPerComponent", in: dictionary, pattern: #"(\d+)"#) == "8",
                  let width = value(of: "Width", in: dictionary, pattern: #"(\d+)"#).flatMap(Int.init),
                  let height = value(of: "Height", in: dictionary, pattern: #"(\d+)"#).flatMap(Int.init),
                  let filter = value(of: "Filter", in: dictionary, pattern: #"\[?\s*/(\w+)\s*\]?"#),
                  filter == "FlateDecode" || filter == "DCTDecode" else { continue }
            // The stream's bytes: after the end of line that follows "stream", up to "endstream" and its end of line.
            var start = streamKeyword.location + streamKeyword.length
            if text.character(at: start) == 0x0D { start += 1 }
            if text.character(at: start) == 0x0A { start += 1 }
            let stop = text.range(of: "endstream", options: .backwards, range: NSRange(location: start, length: end.location - start))
            guard stop.location != NSNotFound else { continue }
            var length = stop.location - start
            if let direct = value(of: "Length", in: dictionary, pattern: #"(\d+)(?!\s+\d+\s+R)"#).flatMap(Int.init), direct <= length {
                length = direct
            }
            found.append(ImageObjects(
                number: Int(text.substring(with: match.range(at: 1))) ?? 0,
                generation: Int(text.substring(with: match.range(at: 2))) ?? 0,
                width: width, height: height, filter: filter,
                predictor: value(of: "Predictor", in: dictionary, pattern: #"(\d+)"#).flatMap(Int.init) ?? 1,
                colors: value(of: "Colors", in: dictionary, pattern: #"(\d+)"#).flatMap(Int.init) ?? 0,
                smask: value(of: "SMask", in: dictionary, pattern: #"(\d+\s+\d+\s+R)"#),
                stream: data.subdata(in: start..<(start + length))
            ))
        }
        return found.filter { !masks.contains("\($0.number) \($0.generation) R") }
    }

    private static func value(of key: String, in dictionary: String, pattern: String) -> String? {
        guard let expression = try? NSRegularExpression(pattern: "/\(key)\\s*" + pattern),
              let match = expression.firstMatch(in: dictionary, range: NSRange(dictionary.startIndex..., in: dictionary)),
              let range = Range(match.range(at: 1), in: dictionary) else { return nil }
        return String(dictionary[range]).replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
    }

    /// The picture, decoded.
    func image() -> CGImage? {
        if filter == "DCTDecode" {
            guard let source = CGImageSourceCreateWithData(stream as CFData, nil),
                  let image = CGImageSourceCreateImageAtIndex(source, 0, nil),
                  let model = image.colorSpace?.model, model == .rgb || model == .monochrome else { return nil }
            return image
        }
        guard stream.count > 2, let inflated = try? (stream.dropFirst(2) as NSData).decompressed(using: .zlib) as Data else { return nil }
        let pixels = width * height
        guard pixels > 0 else { return nil }
        // Gray or RGB, told by how many bytes there are.
        let fits = { (count: Int) in predictor >= 10 ? inflated.count == height * (width * count + 1) : inflated.count == pixels * count }
        let components = colors > 0 ? colors : ([1, 3].first(where: fits) ?? 0)
        guard components == 1 || components == 3, fits(components) else { return nil }
        var raw = inflated
        if predictor >= 10 {
            guard let unfiltered = Self.unpredict(inflated, width: width, height: height, components: components) else { return nil }
            raw = unfiltered
        } else if predictor != 1 {
            return nil
        }
        guard raw.count >= pixels * components, let provider = CGDataProvider(data: raw.prefix(pixels * components) as CFData) else { return nil }
        let space = components == 1 ? CGColorSpaceCreateDeviceGray() : CGColorSpaceCreateDeviceRGB()
        return CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 8 * components,
                       bytesPerRow: width * components, space: space, bitmapInfo: CGBitmapInfo(rawValue: 0),
                       provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }

    /// Undoes PNG row filters (predictors 10–15).
    static func unpredict(_ data: Data, width: Int, height: Int, components: Int) -> Data? {
        let rowLength = width * components
        guard data.count >= (rowLength + 1) * height else { return nil }
        let input = [UInt8](data)
        var output = [UInt8](repeating: 0, count: rowLength * height)
        for row in 0..<height {
            let type = input[row * (rowLength + 1)]
            let source = row * (rowLength + 1) + 1
            let target = row * rowLength
            for column in 0..<rowLength {
                let x = Int(input[source + column])
                let left = column >= components ? Int(output[target + column - components]) : 0
                let up = row > 0 ? Int(output[target - rowLength + column]) : 0
                let upLeft = row > 0 && column >= components ? Int(output[target - rowLength + column - components]) : 0
                let value: Int
                switch type {
                case 0: value = x
                case 1: value = x + left
                case 2: value = x + up
                case 3: value = x + (left + up) / 2
                case 4:
                    let p = left + up - upLeft
                    let pa = abs(p - left), pb = abs(p - up), pc = abs(p - upLeft)
                    value = x + (pa <= pb && pa <= pc ? left : (pb <= pc ? up : upLeft))
                default: return nil
                }
                output[target + column] = UInt8(value & 0xFF)
            }
        }
        return Data(output)
    }

    /// A new version of this object, as a JPEG no larger than `maximumSide` pixels, or nil if that isn't clearly smaller.
    func recompressed(maximumSide: CGFloat, quality: CGFloat) -> (number: Int, generation: Int, body: Data)? {
        guard let image = image() else { return nil }
        let scale = min(1, maximumSide / CGFloat(max(image.width, image.height)))
        let target = scale < 1 ? Self.scaled(image, by: scale) : image
        guard let target else { return nil }
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, target, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(destination), Double(output.length) < Double(stream.count) * 0.9 else { return nil }
        let gray = target.colorSpace?.model == .monochrome
        var dictionary = "<< /Type /XObject /Subtype /Image /Width \(target.width) /Height \(target.height) "
        dictionary += "/ColorSpace /\(gray ? "DeviceGray" : "DeviceRGB") /BitsPerComponent 8 /Filter /DCTDecode "
        if let smask { dictionary += "/SMask \(smask) " }
        dictionary += "/Length \(output.length) >>"
        var body = Data("\(number) \(generation) obj\n\(dictionary)\nstream\n".utf8)
        body.append(output as Data)
        body.append(Data("\nendstream\nendobj\n".utf8))
        return (number, generation, body)
    }

    private static func scaled(_ image: CGImage, by scale: CGFloat) -> CGImage? {
        let width = max(1, Int(CGFloat(image.width) * scale)), height = max(1, Int(CGFloat(image.height) * scale))
        let gray = image.colorSpace?.model == .monochrome
        guard let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: gray ? CGColorSpaceCreateDeviceGray() : CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: gray ? CGImageAlphaInfo.none.rawValue : CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        context.interpolationQuality = .high
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        return context.makeImage()
    }

    /// `data` with new versions of some objects appended as an incremental update (a new cross-reference section
    /// pointing at them). PDFKit reads the update and then writes a clean file without the old versions.
    static func update(_ data: Data, replacing objects: [(number: Int, generation: Int, body: Data)]) -> Data? {
        guard let text = String(data: data, encoding: .isoLatin1) else { return nil }
        let last = { (pattern: String) -> String? in
            guard let expression = try? NSRegularExpression(pattern: pattern),
                  let match = expression.matches(in: text, range: NSRange(text.startIndex..., in: text)).last,
                  let range = Range(match.range(at: 1), in: text) else { return nil }
            return String(text[range])
        }
        guard let previous = last(#"startxref\s+(\d+)"#), let root = last(#"/Root\s+(\d+\s+\d+\s+R)"#) else { return nil }
        let highest = (try? NSRegularExpression(pattern: #"(\d+)\s+\d+\s+obj\b"#))?
            .matches(in: text, range: NSRange(text.startIndex..., in: text))
            .compactMap { Range($0.range(at: 1), in: text).flatMap { Int(text[$0]) } }.max() ?? 0
        let size = max(highest + 1, Int(last(#"/Size\s+(\d+)"#) ?? "0") ?? 0)
        let info = last(#"/Info\s+(\d+\s+\d+\s+R)"#)

        var result = data
        result.append(Data("\n".utf8))
        var offsets: [(Int, Int, Int)] = []
        for object in objects.sorted(by: { $0.number < $1.number }) {
            offsets.append((object.number, object.generation, result.count))
            result.append(object.body)
        }
        let xref = result.count
        var table = "xref\n"
        for (number, generation, offset) in offsets {
            table += "\(number) 1\n" + String(format: "%010d %05d n \n", offset, generation)
        }
        table += "trailer\n<< /Size \(size) /Root \(root) \(info.map { "/Info \($0) " } ?? "")/Prev \(previous) >>\nstartxref\n\(xref)\n%%EOF\n"
        result.append(Data(table.utf8))
        return result
    }
}
