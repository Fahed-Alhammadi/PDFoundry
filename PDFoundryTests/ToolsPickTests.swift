import PDFKit
import Testing
import UIKit
@testable import PDFoundry

/// Files chosen for a tool on the Tools tab: how they are picked, merged and never lost.
@MainActor
struct ToolsPickTests {
    private let folder: URL
    private let library: URL
    private let elsewhere: URL

    init() throws {
        folder = FileManager.default.temporaryDirectory.appending(path: "ToolsPickTests-\(UUID().uuidString)")
        library = folder.appending(path: "Library")
        elsewhere = folder.appending(path: "Elsewhere")
        try FileManager.default.createDirectory(at: library, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: elsewhere, withIntermediateDirectories: true)
    }

    private func makeStore() -> DocumentStore {
        DocumentStore(directory: library, linksFile: folder.appending(path: "opened.json"), copyRoots: [])
    }

    private func makePDF(named name: String, pages: Int, in directory: URL) throws -> URL {
        let data = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792)).pdfData { context in
            for _ in 0..<pages { context.beginPage() }
        }
        let url = directory.appending(path: "\(name).pdf")
        try data.write(to: url)
        return url
    }

    private func item(_ url: URL, modified: Date = .distantPast) -> PDFItem {
        PDFItem(url: url, name: url.deletingPathExtension().lastPathComponent, pageCount: 1, size: 1, modified: modified, isLocked: false)
    }

    // MARK: Merge

    /// Found in review: Lease is opened in place, picked again in Files for Merge with "Delete Originals" on, and it
    /// vanished from Documents.
    @Test func mergingPickedFilesNeverDeletesOrForgetsThem() async throws {
        let store = makeStore()
        let lease = try makePDF(named: "Lease", pages: 2, in: elsewhere)
        let other = try makePDF(named: "Other", pages: 1, in: elsewhere)
        try await store.open([lease])

        let pickedLease = try #require(await DocumentStore.pickedItem(lease))
        let pickedOther = try #require(await DocumentStore.pickedItem(other))
        #expect(pickedLease.isPicked && pickedOther.isPicked)

        let merged = try await store.merge([pickedLease, pickedOther], named: "Both", deleteOriginals: true)

        #expect(FileManager.default.fileExists(atPath: lease.path))
        #expect(FileManager.default.fileExists(atPath: other.path))
        #expect(store.items.contains { $0.isExternal && $0.name == "Lease" }, "the file opened in place is still listed")
        #expect(PDFDocument(url: merged)?.pageCount == 3)
    }

    @Test func deletingOriginalsIsOfferedOnlyForDocumentsKeptInPDFoundry() async throws {
        let store = makeStore()
        let kept = try makePDF(named: "Kept", pages: 1, in: library)
        await store.refresh()
        let keptItem = try #require(store.items.first { $0.url.lastPathComponent == kept.lastPathComponent })
        let picked = try #require(await DocumentStore.pickedItem(try makePDF(named: "Picked", pages: 1, in: elsewhere)))

        #expect(!MergeSheet.offersDeletingOriginals([picked]))
        #expect(MergeSheet.offersDeletingOriginals([keptItem, picked]))
    }

    @Test func pickedFilesAreReadInFull() async throws {
        let url = try makePDF(named: "Report", pages: 3, in: elsewhere)
        let picked = try #require(await DocumentStore.pickedItem(url))
        #expect(picked.pageCount == 3)
        #expect(picked.size == Int64(try Data(contentsOf: url).count))
        #expect(picked.isExternal)

        let notPDF = elsewhere.appending(path: "Notes.pdf")
        try Data("hello".utf8).write(to: notPDF)
        #expect(await DocumentStore.pickedItem(notPDF) == nil)
    }

    // MARK: Picker selection

    @Test func aFileIsPickedOnceInTheOrderItWasPicked() {
        let a = elsewhere.appending(path: "A.pdf")
        let b = elsewhere.appending(path: "B.pdf")
        var selection = PickSelection()

        selection.toggle(b)
        selection.toggle(a)
        let addedAgain = selection.add(URL(filePath: elsewhere.path + "/./B.pdf"))
        #expect(!addedAgain, "the same file again is not added")
        #expect(selection.count == 2)

        // A list refresh makes new item values for the same files: the ticks still find them.
        let refreshed = [item(a, modified: .now), item(b, modified: .now)]
        #expect(selection.items(from: refreshed).map(\.name) == ["B", "A"])
        #expect(selection.contains(refreshed[0]))

        selection.toggle(b)
        #expect(selection.items(from: refreshed).map(\.name) == ["A"])
    }

    @Test func aFileTickedInTheListAndPickedInFilesIsMergedOnce() async throws {
        let lease = try makePDF(named: "Lease", pages: 1, in: elsewhere)
        var selection = PickSelection()
        selection.toggle(lease)
        let fromFiles = try #require(await DocumentStore.pickedItem(lease))

        let addedAgain = selection.add(fromFiles.url)
        #expect(!addedAgain)
        // The picked copy wins, so the file is never treated as deletable.
        let chosen = selection.items(from: [fromFiles, item(lease)])
        #expect(chosen.count == 1 && chosen[0].isPicked)
    }
}
