import Foundation
import Testing
@testable import PDFoundry

/// The Tools tab: which tools exist today and where they sit.
@MainActor
struct ToolsCatalogTests {
    @Test func everySectionIsShownNowThatEnhanceHasTools() {
        #expect(ToolSection.shown(scanAvailable: true) == [.create, .combine, .protect, .enhance])
        #expect(ToolSection.enhance.title == "Enhance")
    }

    @Test func toolsUseTheSharedNames() {
        #expect(ToolSection.create.title == "Create")
        #expect(ToolSection.combine.title == "Combine & split")
        #expect(ToolSection.protect.title == "Protect & optimize")
        #expect(ToolSection.create.tools(scanAvailable: true).map(\.title) == ["Scan Document", "Create from Photos", "Export as Images"])
    }

    @Test func catalogShowsEveryTool() {
        #expect(ToolSection.combine.tools(scanAvailable: true).map(\.title) == ["Merge PDFs", "Extract Pages", "Split PDF"])
        #expect(ToolSection.protect.tools(scanAvailable: true).map(\.title) == ["Password-protect", "Compress", "Redact"])
        #expect(ToolSection.enhance.tools(scanAvailable: true).map(\.title) == ["Make Searchable", "Page Numbers", "Watermark"])
        #expect(PDFTool.split.detail == "Make several PDFs from one")
        #expect(PDFTool.protect.detail == "Lock a PDF with a password")
        #expect(PDFTool.exportImages.detail == "Save pages as pictures")
        #expect(PDFTool.compress.detail == "Make a PDF smaller")
        #expect(PDFTool.redact.detail == "Black out text for good")
        #expect(PDFTool.makeSearchable.detail == "Find text in scanned pages")
        #expect(PDFTool.pageNumbers.detail == "Number every page")
        #expect(PDFTool.watermark.detail == "Stamp text across pages")
    }

    @Test func toolsThatChangeADocumentOpenItInTheReader() {
        #expect(PDFTool.pageNumbers.readerTool == .pageNumbers)
        #expect(PDFTool.watermark.readerTool == .watermark)
        #expect(PDFTool.makeSearchable.readerTool == .makeSearchable)
        #expect(PDFTool.redact.readerTool == .redact)
        #expect(PDFTool.compress.readerTool == nil && PDFTool.exportImages.readerTool == nil && PDFTool.split.readerTool == nil)
    }

    @Test func scanIsLeftOutWhereTheCameraCantScan() {
        #expect(ToolSection.create.tools(scanAvailable: false) == [.photos, .exportImages])
        #expect(ToolSection.shown(scanAvailable: false) == [.create, .combine, .protect, .enhance])
    }

    @Test func theStoreHandsTheToolToTheReaderOnce() {
        let store = DocumentStore(directory: FileManager.default.temporaryDirectory, copyRoots: [])
        let url = URL(filePath: "/tmp/Lease.pdf")
        store.open(url, with: .watermark)
        #expect(store.pendingOpen == url)
        #expect(store.takeReaderTool(for: URL(filePath: "/tmp/Other.pdf")) == nil)
        #expect(store.takeReaderTool(for: url) == .watermark)
        #expect(store.takeReaderTool(for: url) == nil)
    }

    @Test func photoLibraryPermissionIsTheOnlyNewOne() throws {
        let info = try #require(Bundle.main.infoDictionary)
        #expect(info["NSPhotoLibraryAddUsageDescription"] as? String == "PDFoundry saves the pages you export to your photo library.")
        #expect(info["NSPhotoLibraryUsageDescription"] == nil)
    }

    @Test func splitButtonDisabledForBadRanges() {
        var plan = SplitPlan(pageCount: 10)
        #expect(plan.mode == .everyN && plan.n == 2, "every 2 pages to start with")
        #expect(plan.canSplit && plan.summary == "Makes 5 files.")

        plan.mode = .everyPage
        #expect(plan.summary == "Makes 10 files.")

        plan.mode = .custom
        for bad in ["", "0", "5-3", "1-11", "a"] {
            plan.ranges = bad
            #expect(!plan.canSplit, "\(bad) can't be split")
            #expect(plan.error != nil)
        }
        plan.ranges = "1-3, 4-6, 9"
        #expect(plan.canSplit && plan.error == nil)
        #expect(plan.summary == "Makes 3 files.")
        plan.ranges = "4"
        #expect(plan.summary == "Makes 1 file.")
    }

    @Test func protectNeedsTheSamePasswordTwice() {
        #expect(!ProtectForm(password: "", confirm: "").canProtect)
        #expect(!ProtectForm(password: "secret", confirm: "").canProtect)
        #expect(!ProtectForm(password: "secret", confirm: "Secret").canProtect)
        #expect(ProtectForm(password: "secret", confirm: "secret").canProtect)
        #expect(ProtectForm.warning == "If you forget the password, the file can't be opened, and it can't be recovered.")
    }

    @Test func mergeRefusesAProtectedSourceAndSaysWhatToDo() {
        #expect(PDFoundryError.protectedSource("Lease").localizedDescription.hasSuffix("Use More › Remove Password first."))
    }
}
