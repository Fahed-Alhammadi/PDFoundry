import Testing
@testable import PDFoundry

/// The Tools tab: which tools exist today and where they sit.
@MainActor
struct ToolsCatalogTests {
    @Test func onlySectionsWithToolsAreShown() {
        #expect(ToolSection.shown(scanAvailable: true) == [.create, .combine, .protect])
        #expect(ToolSection.enhance.tools(scanAvailable: true).isEmpty)
    }

    @Test func toolsUseTheSharedNames() {
        #expect(ToolSection.create.title == "Create")
        #expect(ToolSection.combine.title == "Combine & split")
        #expect(ToolSection.protect.title == "Protect & optimize")
        #expect(ToolSection.create.tools(scanAvailable: true).map(\.title) == ["Scan Document", "Create from Photos"])
    }

    @Test func catalogShowsSplitAndProtect() {
        #expect(ToolSection.combine.tools(scanAvailable: true).map(\.title) == ["Merge PDFs", "Extract Pages", "Split PDF"])
        #expect(ToolSection.protect.tools(scanAvailable: true).map(\.title) == ["Password-protect"])
        #expect(PDFTool.split.detail == "Make several PDFs from one")
        #expect(PDFTool.protect.detail == "Lock a PDF with a password")
        #expect(!ToolSection.shown(scanAvailable: true).contains(.enhance))
    }

    @Test func scanIsLeftOutWhereTheCameraCantScan() {
        #expect(ToolSection.create.tools(scanAvailable: false) == [.photos])
        #expect(ToolSection.shown(scanAvailable: false) == [.create, .combine, .protect])
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
