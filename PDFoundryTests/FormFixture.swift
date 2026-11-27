import Foundation
import PDFKit
@testable import PDFoundry

/// A one-page PDF form written by hand, as forms made in Acrobat are: text, multi-line text, a checkbox, a radio
/// group (one field with two buttons), a dropdown, a signature field and a read-only text field.
/// (Widgets made with PDFKit lose the radio buttons' own on-names when written, so they can't stand in for a real form.)
enum FormFixture {
    static let fieldNames: Set<String> = ["name", "notes", "agree", "drink", "colour", "signature", "id"]

    static func write(to url: URL, password: String? = nil) throws {
        let data = Data(source().utf8)
        if let password {
            guard let document = PDFDocument(data: data),
                  document.write(to: url, withOptions: PDFOperations.writeOptions(password: password)) else {
                throw PDFoundryError.writeFailed
            }
        } else {
            try data.write(to: url)
        }
    }

    private static func stream(_ dictionary: String, _ content: String) -> String {
        "<< \(dictionary) /Length \(content.utf8.count) >>\nstream\n\(content)\nendstream"
    }

    static func source() -> String {
        let box = "0 0 18 18"
        let objects: [String] = [
            // 1 catalog, 2 pages, 3 page
            "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R 5 0 R 6 0 R 7 0 R 10 0 R 11 0 R 12 0 R] /DA (/Helv 12 Tf 0 g) /DR << /Font << /Helv 13 0 R /ZaDb 17 0 R >> >> >> >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 14 0 R /Resources << /Font << /Helv 13 0 R >> >> /Annots [4 0 R 5 0 R 6 0 R 8 0 R 9 0 R 10 0 R 11 0 R 12 0 R] >>",
            // 4 name, 5 notes (multi-line), 6 agree (checkbox)
            "<< /Type /Annot /Subtype /Widget /FT /Tx /T (name) /Rect [72 680 372 704] /DA (/Helv 12 Tf 0 g) /F 4 /P 3 0 R /V () >>",
            "<< /Type /Annot /Subtype /Widget /FT /Tx /Ff 4096 /T (notes) /Rect [72 560 372 650] /DA (/Helv 12 Tf 0 g) /F 4 /P 3 0 R /V () >>",
            "<< /Type /Annot /Subtype /Widget /FT /Btn /T (agree) /Rect [72 520 90 538] /F 4 /P 3 0 R /V /Off /AS /Off /MK << /CA (4) >> /DA (/ZaDb 0 Tf 0 g) /AP << /N << /Yes 15 0 R /Off 16 0 R >> >> >>",
            // 7 drink (radio group), 8 and 9 its buttons
            "<< /FT /Btn /Ff 49152 /T (drink) /V /Off /Kids [8 0 R 9 0 R] >>",
            "<< /Type /Annot /Subtype /Widget /Parent 7 0 R /Rect [72 480 90 498] /F 4 /P 3 0 R /AS /Off /MK << /CA (l) >> /DA (/ZaDb 0 Tf 0 g) /AP << /N << /Tea 15 0 R /Off 16 0 R >> >> >>",
            "<< /Type /Annot /Subtype /Widget /Parent 7 0 R /Rect [152 480 170 498] /F 4 /P 3 0 R /AS /Off /MK << /CA (l) >> /DA (/ZaDb 0 Tf 0 g) /AP << /N << /Coffee 15 0 R /Off 16 0 R >> >> >>",
            // 10 colour (dropdown), 11 signature, 12 id (read-only)
            "<< /Type /Annot /Subtype /Widget /FT /Ch /Ff 131072 /T (colour) /Rect [72 440 232 464] /DA (/Helv 12 Tf 0 g) /F 4 /P 3 0 R /Opt [(Red) (Green) (Blue)] /V (Red) >>",
            "<< /Type /Annot /Subtype /Widget /FT /Sig /T (signature) /Rect [72 340 312 400] /F 4 /P 3 0 R >>",
            "<< /Type /Annot /Subtype /Widget /FT /Tx /Ff 1 /T (id) /Rect [72 300 232 324] /DA (/Helv 12 Tf 0 g) /F 4 /P 3 0 R /V (A-100) >>",
            // 13 font, 14 page text, 15 and 16 button appearances, 17 ZapfDingbats
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
            stream("", """
            BT /Helv 22 Tf 72 740 Td (Application form) Tj ET
            BT /Helv 10 Tf 72 708 Td (Name) Tj 0 -54 Td (Notes) Tj 0 -84 Td (I agree) Tj 0 -40 Td (Tea         Coffee) Tj ET
            BT /Helv 10 Tf 72 468 Td (Colour) Tj 0 -60 Td (Signature) Tj 0 -60 Td (ID) Tj ET
            0.6 G 72 340 240 60 re S
            """),
            stream("/Type /XObject /Subtype /Form /BBox [\(box)]", "q 0.07 0.08 0.11 rg 3 3 12 12 re f Q"),
            stream("/Type /XObject /Subtype /Form /BBox [\(box)]", "q 0.5 G 0.5 0.5 17 17 re S Q"),
            "<< /Type /Font /Subtype /Type1 /BaseFont /ZapfDingbats >>",
        ]
        var text = "%PDF-1.7\n"
        var offsets: [Int] = []
        for (index, body) in objects.enumerated() {
            offsets.append(text.utf8.count)
            text += "\(index + 1) 0 obj\n\(body)\nendobj\n"
        }
        let xref = text.utf8.count
        text += "xref\n0 \(objects.count + 1)\n0000000000 65535 f \n"
        for offset in offsets { text += String(format: "%010d 00000 n \n", offset) }
        text += "trailer\n<< /Size \(objects.count + 1) /Root 1 0 R >>\nstartxref\n\(xref)\n%%EOF\n"
        return text
    }
}
