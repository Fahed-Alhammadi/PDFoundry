import Testing
@testable import PDFoundry

/// The Tools tab: which tools exist today and where they sit.
@MainActor
struct ToolsCatalogTests {
    @Test func onlySectionsWithToolsAreShown() {
        #expect(ToolSection.shown(scanAvailable: true) == [.create, .combine])
        #expect(ToolSection.protect.tools(scanAvailable: true).isEmpty)
        #expect(ToolSection.enhance.tools(scanAvailable: true).isEmpty)
    }

    @Test func toolsUseTheSharedNames() {
        #expect(ToolSection.create.title == "Create")
        #expect(ToolSection.combine.title == "Combine & split")
        #expect(ToolSection.create.tools(scanAvailable: true).map(\.title) == ["Scan Document", "Create from Photos"])
        #expect(ToolSection.combine.tools(scanAvailable: true).map(\.title) == ["Merge PDFs", "Extract Pages"])
    }

    @Test func scanIsLeftOutWhereTheCameraCantScan() {
        #expect(ToolSection.create.tools(scanAvailable: false) == [.photos])
        #expect(ToolSection.shown(scanAvailable: false) == [.create, .combine])
    }
}
