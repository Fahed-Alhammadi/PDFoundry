import Foundation

/// Hand-written PDFs with binary streams, for tests that need exact objects (image colour spaces, indirect lengths,
/// incremental updates, structure trees).
enum RawPDF {
    /// A PDF with the given objects (number → body, written as "n 0 obj body endobj"). `trailer` goes into the trailer
    /// dictionary along with /Size.
    static func build(_ objects: [Int: Data], trailer: String = "/Root 1 0 R") -> Data {
        var out = Data("%PDF-1.7\n%\u{E2}\u{E3}\n".utf8)
        var offsets: [Int: Int] = [:]
        for number in objects.keys.sorted() {
            offsets[number] = out.count
            out.append(Data("\(number) 0 obj\n".utf8))
            out.append(objects[number]!)
            out.append(Data("\nendobj\n".utf8))
        }
        let size = (objects.keys.max() ?? 0) + 1
        let xref = out.count
        var table = "xref\n0 \(size)\n0000000000 65535 f \n"
        for number in 1..<size {
            table += offsets[number].map { String(format: "%010d 00000 n \n", $0) } ?? "0000000000 65535 f \n"
        }
        table += "trailer\n<< /Size \(size) \(trailer) >>\nstartxref\n\(xref)\n%%EOF\n"
        out.append(Data(table.utf8))
        return out
    }

    /// Appends an incremental update that replaces `objects`.
    static func update(_ data: Data, with objects: [Int: Data], root: String = "1 0 R") -> Data {
        let text = String(decoding: data.suffix(64), as: UTF8.self)
        let previous = text.components(separatedBy: "startxref\n").last?.components(separatedBy: "\n").first ?? "0"
        var out = data
        var offsets: [Int: Int] = [:]
        for number in objects.keys.sorted() {
            offsets[number] = out.count
            out.append(Data("\(number) 0 obj\n".utf8))
            out.append(objects[number]!)
            out.append(Data("\nendobj\n".utf8))
        }
        let xref = out.count
        var table = "xref\n"
        for number in objects.keys.sorted() {
            table += "\(number) 1\n" + String(format: "%010d 00000 n \n", offsets[number]!)
        }
        let size = (objects.keys.max() ?? 0) + 50
        table += "trailer\n<< /Size \(size) /Root \(root) /Prev \(previous) >>\nstartxref\n\(xref)\n%%EOF\n"
        out.append(Data(table.utf8))
        return out
    }

    static func object(_ text: String) -> Data { Data(text.utf8) }

    /// A stream object: `dictionary` (without /Length unless `length` is given as text) and its bytes.
    static func stream(_ dictionary: String, _ bytes: Data, length: String? = nil) -> Data {
        var out = Data("<< \(dictionary) /Length \(length ?? String(bytes.count)) >>\nstream\n".utf8)
        out.append(bytes)
        out.append(Data("\nendstream".utf8))
        return out
    }

    /// zlib-wrapped deflate, as /FlateDecode expects.
    static func flate(_ data: Data) -> Data {
        let raw = (try? (data as NSData).compressed(using: .zlib) as Data) ?? Data()
        var a: UInt32 = 1, b: UInt32 = 0
        for byte in data {
            a = (a + UInt32(byte)) % 65521
            b = (b + a) % 65521
        }
        var out = Data([0x78, 0x9C])
        out.append(raw)
        let adler = (b << 16) | a
        out.append(contentsOf: [UInt8(adler >> 24), UInt8(adler >> 16 & 0xFF), UInt8(adler >> 8 & 0xFF), UInt8(adler & 0xFF)])
        return out
    }

    /// Noisy pixels (compress badly losslessly), `components` bytes per pixel.
    static func noise(width: Int, height: Int, components: Int, seed: UInt32 = 7) -> Data {
        var state = seed
        var bytes = [UInt8](repeating: 0, count: width * height * components)
        for index in bytes.indices {
            state = state &* 1_103_515_245 &+ 12345
            bytes[index] = UInt8(truncatingIfNeeded: (index / (width * components)) &+ Int(state >> 27))
        }
        return Data(bytes)
    }

    /// A one-page PDF showing image XObject 5 over the whole page; `extra` objects are added.
    static func imagePage(_ image: Data, extra: [Int: Data] = [:]) -> Data {
        var objects: [Int: Data] = [
            1: object("<< /Type /Catalog /Pages 2 0 R >>"),
            2: object("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            3: object("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /XObject << /Im1 5 0 R >> >> >>"),
            4: stream("", Data("q 612 0 0 792 0 0 cm /Im1 Do Q".utf8)),
            5: image,
        ]
        objects.merge(extra) { $1 }
        return build(objects)
    }
}
