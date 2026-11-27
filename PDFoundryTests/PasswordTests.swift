import PDFKit
import Testing
import UIKit
@testable import PDFoundry

/// Password-protected files: who may edit them, and that every write keeps (or, when asked, removes) the password.
@MainActor
struct PasswordTests {
    private let folder: URL

    init() throws {
        folder = FileManager.default.temporaryDirectory.appending(path: "PasswordTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    }

    private func plainData(pages: Int = 1) -> Data {
        UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792)).pdfData { context in
            for index in 0..<pages {
                context.beginPage()
                ("Page \(index + 1)" as NSString).draw(at: CGPoint(x: 72, y: 72), withAttributes: [.font: UIFont.systemFont(ofSize: 24)])
            }
        }
    }

    /// Writes a test file. `user` nil with an `owner` makes a file with restrictions only (it opens without asking).
    private func makePDF(named name: String = "Secret", user: String? = nil, owner: String? = nil) throws -> URL {
        let url = folder.appending(path: "\(name).pdf")
        let document = try #require(PDFDocument(data: plainData()))
        var options: [PDFDocumentWriteOption: Any] = [:]
        if let owner { options[.ownerPasswordOption] = owner }
        if let user { options[.userPasswordOption] = user }
        if owner != nil { options[.accessPermissionsOption] = NSNumber(value: 0) }
        #expect(document.write(to: url, withOptions: options))
        return url
    }

    private func makeProtected(_ password: String = "open-me") throws -> URL {
        let url = folder.appending(path: "Protected.pdf")
        let document = try #require(PDFDocument(data: plainData()))
        #expect(document.write(to: url, withOptions: PDFOperations.writeOptions(password: password)))
        return url
    }

    /// A model showing the document in a real PDFView, as the reader does.
    private func open(_ url: URL, password: String? = nil) async throws -> ReaderModel {
        let model = ReaderModel(url: url)
        await model.load()
        if let password { #expect(model.unlock(with: password)) }
        let view = PDFView(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
        view.autoScales = true
        view.document = model.document
        view.layoutIfNeeded()
        model.pdfView = view
        return model
    }

    private func placeStamp(_ model: ReaderModel, y: CGFloat = 300) {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 200, height: 80)).image { context in
            UIColor.black.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 200, height: 80))
        }
        model.beginPlacing(signature: image)
        model.placement?.rect = CGRect(x: 100, y: y, width: 150, height: 60)
        model.commitPlacement()
    }

    /// Reads the file from disk as another app would: it must ask for the password, and open with it.
    private func reopen(_ url: URL, password: String) throws -> PDFDocument {
        let document = try #require(PDFDocument(url: url))
        #expect(document.isEncrypted && document.isLocked, "the file still asks for its password")
        #expect(!document.unlock(withPassword: "wrong"))
        #expect(document.unlock(withPassword: password))
        return document
    }

    private func stamps(_ document: PDFDocument) -> Int {
        document.page(at: 0)?.annotations.filter { $0.type == "Stamp" }.count ?? 0
    }

    @Test func unlockedWithOwnerPasswordCanEdit() async throws {
        let same = try await open(try makeProtected("pw"))
        #expect(same.isLocked && !same.canEdit)
        #expect(same.unlock(with: "pw"))
        #expect(same.canEdit && same.isProtected)

        let separate = try makePDF(named: "Separate", user: "u", owner: "o")
        let withUser = try await open(separate, password: "u")
        #expect(!withUser.canEdit, "the user password gives limited access only")
        #expect(!withUser.isProtected)
        let withOwner = try await open(separate, password: "o")
        #expect(withOwner.canEdit)
    }

    @Test func restrictionOnlyFileStaysReadOnly() async throws {
        let model = try await open(try makePDF(named: "Restricted", user: "", owner: "o"))
        #expect(!model.isLocked, "it opens without asking")
        #expect(model.document?.isEncrypted == true)
        #expect(!model.canEdit)
        #expect(!model.protect(with: "new"))
    }

    @Test func editOfProtectedFileStaysProtected() async throws {
        let url = try makeProtected()
        let model = try await open(url, password: "open-me")

        placeStamp(model)
        #expect(stamps(try reopen(url, password: "open-me")) == 1)
        model.undo()
        #expect(stamps(try reopen(url, password: "open-me")) == 0)
        placeStamp(model, y: 400)

        let reloaded = try reopen(url, password: "open-me")
        #expect(stamps(reloaded) == 1)
        #expect(try FileManager.default.contentsOfDirectory(atPath: folder.path) == ["Protected.pdf"], "no plain copy beside it")
    }

    @Test func revertToAnEncryptedOriginalKeepsThePassword() async throws {
        let url = try makeProtected()
        let before = try Data(contentsOf: url)
        let model = try await open(url, password: "open-me")
        placeStamp(model)

        #expect(model.revertToOriginal())

        #expect(try Data(contentsOf: url) == before)
        #expect(!model.isLocked && model.canEdit, "still unlocked in the reader")
        placeStamp(model)
        #expect(stamps(try reopen(url, password: "open-me")) == 1)
    }

    @Test func protectWhileOpenThenEditStaysProtected() async throws {
        let url = folder.appending(path: "Plain.pdf")
        try plainData().write(to: url)
        let model = try await open(url)
        #expect(!model.isProtected)

        #expect(model.protect(with: "new-pw"))
        #expect(model.isProtected && model.canEdit)
        _ = try reopen(url, password: "new-pw")

        placeStamp(model)
        #expect(stamps(try reopen(url, password: "new-pw")) == 1)

        let shared = try #require(model.temporaryCopy())
        #expect(PDFDocument(url: shared)?.isLocked == true, "a shared copy keeps the password too")
    }

    @Test func removePasswordWritesPlainFile() async throws {
        let url = try makeProtected()
        let model = try await open(url, password: "open-me")

        #expect(model.removePassword())

        #expect(!model.isProtected && model.canEdit)
        let plain = try #require(PDFDocument(url: url))
        #expect(!plain.isEncrypted && !plain.isLocked)
        placeStamp(model)
        let edited = try #require(PDFDocument(url: url))
        #expect(!edited.isEncrypted)
        #expect(stamps(edited) == 1)
    }

    @Test func protectRefusesAlreadyProtected() async throws {
        let store = DocumentStore(directory: folder.appending(path: "Library"), linksFile: folder.appending(path: "opened.json"), copyRoots: [])
        let protected = try makeProtected()
        let before = try Data(contentsOf: protected)

        await #expect(throws: PDFoundryError.self) {
            try await store.protect(protected, password: "other")
        }
        #expect(try Data(contentsOf: protected) == before)

        let plain = folder.appending(path: "Plain.pdf")
        try plainData(pages: 2).write(to: plain)
        try await store.protect(plain, password: "pw")
        let reopened = try reopen(plain, password: "pw")
        #expect(reopened.pageCount == 2)
        #expect(Set(try FileManager.default.contentsOfDirectory(atPath: folder.path)) == ["Protected.pdf", "Plain.pdf"])
    }

    @Test func aFileOpenedInPlaceStaysListedAfterItIsProtected() async throws {
        let elsewhere = folder.appending(path: "Elsewhere")
        try FileManager.default.createDirectory(at: elsewhere, withIntermediateDirectories: true)
        let store = DocumentStore(directory: folder.appending(path: "Library"), linksFile: folder.appending(path: "opened.json"), copyRoots: [])
        let lease = elsewhere.appending(path: "Lease.pdf")
        try plainData().write(to: lease)
        try await store.open([lease])
        #expect(store.items.count == 1)
        func identity() throws -> Int? { try FileManager.default.attributesOfItem(atPath: lease.path)[.systemFileNumber] as? Int }
        let before = try identity()

        try await store.protect(lease, password: "pw")
        #expect(try identity() == before, "the file is overwritten, not replaced, so its bookmark keeps working")
        let model = ReaderModel(url: lease)
        await model.load()
        #expect(model.unlock(with: "pw"))
        model.pagesDidChange() // a save from the reader
        #expect(try identity() == before)

        #expect(store.items.map(\.name) == ["Lease"])
        #expect(store.items.first?.isLocked == true && store.items.first?.isExternal == true)
        await store.refresh()
        #expect(store.items.map(\.name) == ["Lease"], "still listed after a later refresh")
        #expect(try FileManager.default.contentsOfDirectory(atPath: elsewhere.path) == ["Lease.pdf"])
    }
}
