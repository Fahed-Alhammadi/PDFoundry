import PDFKit
import Testing
import UIKit
@testable import PDFoundry

/// Form fields: filled values are saved as field values (never flattened), can be undone, and read-only fields
/// stay as they are.
@MainActor
struct FormFieldTests {
    private let folder: URL

    init() throws {
        folder = FileManager.default.temporaryDirectory.appending(path: "FormFieldTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    static func makeForm(at url: URL, password: String? = nil) throws {
        try FormFixture.write(to: url, password: password)
    }

    private func formURL(password: String? = nil) throws -> URL {
        let url = folder.appending(path: "Form.pdf")
        try Self.makeForm(at: url, password: password)
        return url
    }

    private func widgets(_ document: PDFDocument?) -> [String: [PDFAnnotation]] {
        Dictionary(grouping: document?.page(at: 0)?.annotations.filter { $0.type == "Widget" } ?? [], by: { $0.fieldName ?? "" })
    }

    private func open(_ url: URL, password: String? = nil) async throws -> ReaderModel {
        let model = ReaderModel(url: url)
        await model.load()
        if let password { #expect(model.unlock(with: password)) }
        let view = PDFView(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        view.document = model.document
        model.pdfView = view
        return model
    }

    @Test func fieldValuesSurviveSaveAsFields() throws {
        let url = try formURL()
        let document = try #require(PDFDocument(url: url))
        var fields = widgets(document)
        #expect(FormFields.set(try #require(fields["name"]?.first), value: .text("Jane Doe")))
        #expect(FormFields.set(try #require(fields["notes"]?.first), value: .text("Line one\nLine two")))
        #expect(FormFields.set(try #require(fields["agree"]?.first), value: .on(true)))
        #expect(FormFields.set(try #require(fields["drink"]?.last), value: .on(true)))
        #expect(FormFields.set(try #require(fields["colour"]?.first), value: .choice("Blue")))
        #expect(document.write(to: url))

        let reloaded = try #require(PDFDocument(url: url))
        FormFields.repair(reloaded) // as the reader does when it opens a document
        fields = widgets(reloaded)
        #expect(fields.count == 7, "every field is still a field")
        #expect(FormFields.value(of: try #require(fields["name"]?.first)) == .text("Jane Doe"))
        #expect(FormFields.value(of: try #require(fields["notes"]?.first)) == .text("Line one\nLine two"))
        #expect(FormFields.value(of: try #require(fields["agree"]?.first)) == .on(true))
        let drinks = try #require(fields["drink"])
        #expect(drinks.map { FormFields.value(of: $0) } == [.on(false), .on(true)])
        #expect(FormFields.value(of: try #require(fields["colour"]?.first)) == .choice("Blue"))
        #expect(FormFields.value(of: try #require(fields["id"]?.first)) == .text("A-100"))
    }

    @Test func choosingARadioButtonTurnsTheOtherOff() throws {
        let url = try formURL()
        let document = try #require(PDFDocument(url: url))
        let drinks = try #require(widgets(document)["drink"])
        #expect(FormFields.set(drinks[0], value: .on(true)))
        #expect(drinks.map { FormFields.value(of: $0) } == [.on(true), .on(false)])
        #expect(FormFields.set(drinks[1], value: .on(true)))
        #expect(drinks.map { FormFields.value(of: $0) } == [.on(false), .on(true)])
    }

    @Test func aRadioChoiceSurvivesSavingAgain() throws {
        let url = try formURL()
        for choice in [1, 0, 1] {
            let document = try #require(PDFDocument(url: url))
            FormFields.repair(document)
            let drinks = try #require(widgets(document)["drink"])
            #expect(FormFields.set(drinks[choice], value: .on(true)))
            #expect(drinks.map { FormFields.value(of: $0) } == [.on(choice == 0), .on(choice == 1)])
            #expect(document.write(to: url))

            let reloaded = try #require(PDFDocument(url: url))
            FormFields.repair(reloaded)
            let saved = try #require(widgets(reloaded)["drink"])
            #expect(saved.map { FormFields.value(of: $0) } == [.on(choice == 0), .on(choice == 1)], "choice \(choice) after saving")
        }
    }

    @Test func arabicFieldValueRoundTrips() throws {
        let url = try formURL()
        let document = try #require(PDFDocument(url: url))
        let arabic = "مرحبا بالعالم"
        let name = try #require(widgets(document)["name"]?.first)
        #expect(FormFields.set(name, value: .text(arabic)))
        #expect(document.write(to: url))

        let reloaded = try #require(PDFDocument(url: url))
        let page = try #require(reloaded.page(at: 0))
        let field = try #require(widgets(reloaded)["name"]?.first)
        #expect(FormFields.value(of: field) == .text(arabic), "saved as real text")
        let rendered = page.thumbnail(of: CGSize(width: 612 * 2, height: 792 * 2), for: .mediaBox)
        #expect(rendered.hasDarkPixel(inPageRect: field.bounds, page: page), "the value is drawn, not a blank box")
    }

    @Test func fieldChangeIsUndoable() async throws {
        let url = try formURL()
        let model = try await open(url)
        let name = try #require(widgets(model.document)["name"]?.first)
        #expect(!model.canUndo)

        // The user types into the field; the reader notices once editing ends.
        name.widgetStringValue = "Jane"
        model.syncFields()
        #expect(model.canUndo)
        #expect(FormFields.value(of: try #require(widgets(PDFDocument(url: url))["name"]?.first)) == .text("Jane"), "saved at once")

        let agree = try #require(widgets(model.document)["agree"]?.first)
        agree.buttonWidgetState = .onState
        model.syncFields()

        model.undo()
        #expect(FormFields.value(of: agree) == .on(false))
        #expect(FormFields.value(of: name) == .text("Jane"), "one step per change")
        model.undo()
        #expect(FormFields.value(of: name) == .text(""))
        #expect(!model.canUndo)
        let saved = widgets(PDFDocument(url: url))
        #expect(FormFields.value(of: try #require(saved["name"]?.first)) == .text(""))
        #expect(FormFields.value(of: try #require(saved["agree"]?.first)) == .on(false))
    }

    @Test func readOnlyFieldIsNotChanged() async throws {
        let url = try formURL()
        let document = try #require(PDFDocument(url: url))
        let id = try #require(widgets(document)["id"]?.first)
        #expect(!FormFields.set(id, value: .text("B-200")))
        #expect(FormFields.value(of: id) == .text("A-100"))
    }

    @Test func fieldsOfAReadOnlyDocumentCantBeChanged() async throws {
        let url = folder.appending(path: "Restricted.pdf")
        let plain = folder.appending(path: "Plain.pdf")
        try Self.makeForm(at: plain)
        let source = try #require(PDFDocument(url: plain))
        #expect(source.write(to: url, withOptions: [.ownerPasswordOption: "o", .userPasswordOption: "", .accessPermissionsOption: NSNumber(value: 0)]))
        let before = try Data(contentsOf: url)

        let model = try await open(url)
        #expect(!model.canEdit)
        let name = try #require(widgets(model.document)["name"]?.first)
        #expect(name.isReadOnly || model.document?.allowsFormFieldEntry == false, "fields can't be typed into")
        name.widgetStringValue = "Jane"
        model.syncFields()
        #expect(!model.canUndo)
        #expect(try Data(contentsOf: url) == before)
    }

    @Test func signatureFieldIsStampedToFit() async throws {
        let url = try formURL()
        let model = try await open(url)
        let field = try #require(widgets(model.document)["signature"]?.first)
        let wide = UIGraphicsImageRenderer(size: CGSize(width: 400, height: 50)).image { context in
            UIColor.black.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 400, height: 50))
        }

        model.sign(field, with: wide)

        let stamp = try #require(model.document?.page(at: 0)?.annotations.first { $0.type == "Stamp" })
        // 240x60 field, 8:1 image: full width, 30 high, centred.
        #expect(abs(stamp.bounds.width - 240) < 0.5 && abs(stamp.bounds.height - 30) < 0.5)
        #expect(abs(stamp.bounds.midX - field.bounds.midX) < 0.5 && abs(stamp.bounds.midY - field.bounds.midY) < 0.5)
        #expect(widgets(model.document)["signature"]?.count == 1, "the field itself stays")
        #expect(model.canUndo)
        #expect(PDFDocument(url: url)?.page(at: 0)?.annotations.contains { $0.type == "Stamp" } == true)
    }

    @Test func fieldsSurviveProtectingAndRemovingThePassword() async throws {
        let url = try formURL(password: "pw")
        let model = try await open(url, password: "pw")
        let name = try #require(widgets(model.document)["name"]?.first)
        name.widgetStringValue = "Jane"
        model.syncFields()

        #expect(model.removePassword())

        let reloaded = try #require(PDFDocument(url: url))
        #expect(!reloaded.isEncrypted)
        let fields = widgets(reloaded)
        #expect(fields.count == 7)
        #expect(FormFields.value(of: try #require(fields["name"]?.first)) == .text("Jane"))
        // Still editable after the reader moved to the plain copy.
        let moved = try #require(widgets(model.document)["name"]?.first)
        moved.widgetStringValue = "Jane Doe"
        model.syncFields()
        #expect(FormFields.value(of: try #require(widgets(PDFDocument(url: url))["name"]?.first)) == .text("Jane Doe"))
    }
}
