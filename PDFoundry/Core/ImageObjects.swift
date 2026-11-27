import Foundation
import ImageIO
import UniformTypeIdentifiers

/// The image objects of a PDF file, found in its bytes, and how to write new versions of them.
///
/// The file is read through byte offsets into the one `Data` (no text copies of it), so memory stays close to one
/// copy of the file plus the one picture being re-encoded. Only pictures that can be re-encoded exactly are taken:
/// 8 bits per component, DeviceGray, DeviceRGB or ICCBased with 1 or 3 components, Flate (with or without a PNG
/// predictor) or JPEG, and no /Decode, /ImageMask or /Mask. Everything else (Indexed, Separation, DeviceN, Lab,
/// CMYK…) is left exactly as it is. A picture with a soft mask keeps it untouched (the mask object is not changed).
nonisolated struct ImageObjects {
    let number: Int
    let generation: Int
    let width: Int
    let height: Int
    let filter: String
    let predictor: Int
    /// 1 (gray) or 3 (RGB), from the colour space.
    let components: Int
    let smask: String?
    private let file: Data
    private let range: Range<Int>

    /// The stream's bytes (shares the file's storage).
    var stream: Data { file[range] }

    // MARK: Finding

    /// One object of the file: where its body starts and its "endobj" begins.
    struct Object {
        let number: Int
        let generation: Int
        let body: Range<Int>
    }

    static func find(in data: Data) -> [ImageObjects] {
        let objects = latestObjects(in: data)
        var masks: Set<Int> = []
        var candidates: [ImageObjects] = []
        for object in objects.values {
            guard let stream = streamRange(of: object, in: data) else { continue }
            let dictionary = text(data, object.body.lowerBound..<stream.keyword)
            if let mask = match(#"/SMask\s+(\d+)\s+\d+\s+R"#, in: dictionary).flatMap(Int.init) { masks.insert(mask) }
            guard match(#"/Subtype\s*/(Image)\b"#, in: dictionary) != nil,
                  match(#"/(ImageMask)\s+true"#, in: dictionary) == nil,
                  match(#"/(Mask)\b"#, in: dictionary) == nil,
                  match(#"/(Decode)\b"#, in: dictionary) == nil,
                  match(#"/(SMaskInData)\b"#, in: dictionary) == nil,
                  match(#"/BitsPerComponent\s+(\d+)"#, in: dictionary) == "8",
                  let width = match(#"/Width\s+(\d+)"#, in: dictionary).flatMap(Int.init), width > 0,
                  let height = match(#"/Height\s+(\d+)"#, in: dictionary).flatMap(Int.init), height > 0,
                  let filter = match(#"/Filter\s*\[?\s*/(\w+)\s*\]?"#, in: dictionary),
                  filter == "FlateDecode" || filter == "DCTDecode",
                  match(#"/Filter\s*\[\s*/\w+\s+/(\w+)"#, in: dictionary) == nil, // one filter only
                  let components = colorComponents(dictionary, objects: objects, data: data),
                  let bytes = exactStream(stream, dictionary: dictionary, objects: objects, data: data) else { continue }
            candidates.append(ImageObjects(
                number: object.number, generation: object.generation, width: width, height: height, filter: filter,
                predictor: match(#"/Predictor\s+(\d+)"#, in: dictionary).flatMap(Int.init) ?? 1,
                components: components,
                smask: match(#"/SMask\s+(\d+\s+\d+\s+R)"#, in: dictionary),
                file: data, range: bytes))
        }
        return candidates.filter { !masks.contains($0.number) }.sorted { $0.number < $1.number }
    }

    /// Every object of the file, keeping only the last version of each object number (an incremental update's
    /// newer version wins, as it does for any reader).
    static func latestObjects(in data: Data) -> [Int: Object] {
        var headers: [(number: Int, generation: Int, start: Int)] = []
        data.withUnsafeBytes { (buffer: UnsafeRawBufferPointer) in
            let bytes = buffer.bindMemory(to: UInt8.self)
            let count = bytes.count
            func isSpace(_ b: UInt8) -> Bool { b == 0x20 || b == 0x0A || b == 0x0D || b == 0x09 || b == 0x0C || b == 0x00 }
            func isDigit(_ b: UInt8) -> Bool { b >= 0x30 && b <= 0x39 }
            var index = 0
            while index + 3 <= count {
                defer { index += 1 }
                guard bytes[index] == 0x6F, bytes[index + 1] == 0x62, bytes[index + 2] == 0x6A else { continue } // "obj"
                if index + 3 < count, !(isSpace(bytes[index + 3]) || bytes[index + 3] == 0x3C || bytes[index + 3] == 0x5B
                                        || bytes[index + 3] == 0x2F || bytes[index + 3] == 0x28) { continue }
                // Back over: spaces, generation, spaces, number.
                var cursor = index - 1
                guard cursor >= 0, isSpace(bytes[cursor]) else { continue }
                while cursor >= 0, isSpace(bytes[cursor]) { cursor -= 1 }
                var generation = 0, scale = 1, digits = 0
                while cursor >= 0, isDigit(bytes[cursor]) {
                    generation += Int(bytes[cursor] - 0x30) * scale; scale *= 10; cursor -= 1; digits += 1
                }
                guard digits > 0, digits < 6, cursor >= 0, isSpace(bytes[cursor]) else { continue }
                while cursor >= 0, isSpace(bytes[cursor]) { cursor -= 1 }
                var number = 0
                scale = 1; digits = 0
                while cursor >= 0, isDigit(bytes[cursor]) {
                    number += Int(bytes[cursor] - 0x30) * scale; scale *= 10; cursor -= 1; digits += 1
                }
                guard digits > 0, digits < 11, cursor < 0 || isSpace(bytes[cursor]) || bytes[cursor] == 0x3E else { continue }
                headers.append((number, generation, index + 3))
            }
        }
        let endobj = Data("endobj".utf8)
        var objects: [Int: Object] = [:]
        for (position, header) in headers.enumerated() {
            // An object ends at its "endobj" (searched only up to the next object's header).
            let limit = position + 1 < headers.count ? headers[position + 1].start : data.count
            guard let end = data.range(of: endobj, in: header.start..<limit)?.lowerBound else { continue }
            objects[header.number] = Object(number: header.number, generation: header.generation, body: header.start..<end)
        }
        return objects
    }

    /// Where the "stream" keyword is, and where the stream's bytes are as told by "endstream".
    private static func streamRange(of object: Object, in data: Data) -> (keyword: Int, bytes: Range<Int>)? {
        guard let keyword = data.range(of: Data("stream".utf8), in: object.body)?.lowerBound,
              let endKeyword = data.range(of: Data("endstream".utf8), options: .backwards, in: object.body)?.lowerBound,
              endKeyword > keyword else { return nil }
        var start = keyword + 6
        if start < data.count, data[start] == 0x0D { start += 1 }
        if start < data.count, data[start] == 0x0A { start += 1 }
        var end = endKeyword
        if end > start, data[end - 1] == 0x0A { end -= 1 }
        if end > start, data[end - 1] == 0x0D { end -= 1 }
        guard end >= start else { return nil }
        return (keyword, start..<end)
    }

    /// The stream's bytes: as long as /Length says (direct, or the object it points at), checked against where
    /// "endstream" is; when /Length can't be used, up to "endstream".
    private static func exactStream(_ stream: (keyword: Int, bytes: Range<Int>), dictionary: String,
                                    objects: [Int: Object], data: Data) -> Range<Int>? {
        let available = stream.bytes
        var length: Int?
        if let indirect = match(#"/Length\s+(\d+)\s+\d+\s+R"#, in: dictionary).flatMap(Int.init) {
            if let object = objects[indirect] {
                length = Int(text(data, object.body).trimmingCharacters(in: .whitespacesAndNewlines))
            }
        } else {
            length = match(#"/Length\s+(\d+)"#, in: dictionary).flatMap(Int.init)
        }
        if let length, length > 0, length <= available.count {
            // The bytes after /Length's end must be only an end of line before "endstream".
            if available.count - length <= 2 { return available.lowerBound..<(available.lowerBound + length) }
        }
        return available.isEmpty ? nil : available
    }

    /// 1 or 3 when the image's colour space is gray or RGB (DeviceGray, DeviceRGB, ICCBased with N 1 or 3), else nil.
    private static func colorComponents(_ dictionary: String, objects: [Int: Object], data: Data) -> Int? {
        func components(of space: String, depth: Int) -> Int? {
            let trimmed = space.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.hasPrefix("/DeviceGray") { return 1 }
            if trimmed.hasPrefix("/DeviceRGB") { return 3 }
            if let icc = match(#"^\[\s*/ICCBased\s+(\d+)\s+\d+\s+R\s*\]"#, in: trimmed).flatMap(Int.init), let object = objects[icc] {
                let n = match(#"/N\s+(\d+)"#, in: text(data, object.body, limit: 2048)).flatMap(Int.init)
                return n == 1 || n == 3 ? n : nil
            }
            if depth < 2, let reference = match(#"^(\d+)\s+\d+\s+R"#, in: trimmed).flatMap(Int.init), let object = objects[reference] {
                return components(of: text(data, object.body, limit: 512), depth: depth + 1)
            }
            return nil
        }
        guard let start = dictionary.range(of: "/ColorSpace") else { return nil }
        return components(of: String(dictionary[start.upperBound...].prefix(200)), depth: 0)
    }

    private static func text(_ data: Data, _ range: Range<Int>, limit: Int = 16_384) -> String {
        let end = min(range.upperBound, range.lowerBound + limit)
        guard end > range.lowerBound else { return "" }
        return String(decoding: data[range.lowerBound..<end], as: UTF8.self)
    }

    private static func match(_ pattern: String, in text: String) -> String? {
        guard let expression = try? NSRegularExpression(pattern: pattern),
              let found = expression.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              let range = Range(found.range(at: 1), in: text) else { return nil }
        return String(text[range]).replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
    }

    // MARK: Decoding

    /// The picture, decoded, or nil when it doesn't decode to exactly /Width × /Height with its colour space.
    func image() -> CGImage? {
        if filter == "DCTDecode" {
            guard let source = CGImageSourceCreateWithData(stream as CFData, nil),
                  let image = CGImageSourceCreateImageAtIndex(source, 0, nil),
                  image.width == width, image.height == height,
                  let model = image.colorSpace?.model,
                  (components == 3 && model == .rgb) || (components == 1 && model == .monochrome) else { return nil }
            return image
        }
        guard stream.count > 2, let inflated = try? (stream.dropFirst(2) as NSData).decompressed(using: .zlib) as Data else { return nil }
        let rowLength = width * components
        var raw = inflated
        if predictor >= 10 {
            guard inflated.count == height * (rowLength + 1),
                  let unfiltered = Self.unpredict(inflated, width: width, height: height, components: components) else { return nil }
            raw = unfiltered
        } else if predictor != 1 {
            return nil
        }
        guard raw.count == rowLength * height, let provider = CGDataProvider(data: raw as CFData) else { return nil }
        let space = components == 1 ? CGColorSpaceCreateDeviceGray() : CGColorSpaceCreateDeviceRGB()
        return CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 8 * components,
                       bytesPerRow: rowLength, space: space, bitmapInfo: CGBitmapInfo(rawValue: 0),
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

    // MARK: Writing

    /// A new version of this object, as a JPEG no larger than `maximumSide` pixels, or nil if it can't be decoded
    /// exactly or the JPEG isn't clearly smaller.
    func recompressed(maximumSide: CGFloat, quality: CGFloat) -> (number: Int, generation: Int, body: Data)? {
        guard let image = image() else { return nil }
        let scale = min(1, maximumSide / CGFloat(max(image.width, image.height)))
        guard let target = scale < 1 ? Self.scaled(image, by: scale, gray: components == 1) : image else { return nil }
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, target, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(destination), Double(output.length) < Double(stream.count) * 0.9 else { return nil }
        var dictionary = "<< /Type /XObject /Subtype /Image /Width \(target.width) /Height \(target.height) "
        dictionary += "/ColorSpace /\(components == 1 ? "DeviceGray" : "DeviceRGB") /BitsPerComponent 8 /Filter /DCTDecode "
        if let smask { dictionary += "/SMask \(smask) " }
        dictionary += "/Length \(output.length) >>"
        var body = Data("\(number) \(generation) obj\n\(dictionary)\nstream\n".utf8)
        body.append(output as Data)
        body.append(Data("\nendstream\nendobj\n".utf8))
        return (number, generation, body)
    }

    private static func scaled(_ image: CGImage, by scale: CGFloat, gray: Bool) -> CGImage? {
        let width = max(1, Int(CGFloat(image.width) * scale)), height = max(1, Int(CGFloat(image.height) * scale))
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
        // The last trailer: found from the last "startxref", either a classic table's trailer or an xref stream.
        let tailStart = max(0, data.count - 2048)
        guard let startxref = data.range(of: Data("startxref".utf8), options: .backwards, in: tailStart..<data.count),
              let previous = match(#"^\s*(\d+)"#, in: text(data, startxref.upperBound..<data.count, limit: 64)).flatMap(Int.init),
              previous < data.count else { return nil }
        let trailerText: String
        if text(data, previous..<data.count, limit: 4).hasPrefix("xref"),
           let trailer = data.range(of: Data("trailer".utf8), in: previous..<data.count) {
            trailerText = text(data, trailer.upperBound..<data.count, limit: 4096)
        } else {
            trailerText = text(data, previous..<data.count, limit: 4096)
        }
        guard let root = match(#"/Root\s+(\d+\s+\d+\s+R)"#, in: trailerText) else { return nil }
        let info = match(#"/Info\s+(\d+\s+\d+\s+R)"#, in: trailerText)
        let highest = latestObjects(in: data).keys.max() ?? 0
        let size = max(highest + 1, match(#"/Size\s+(\d+)"#, in: trailerText).flatMap(Int.init) ?? 0)

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
