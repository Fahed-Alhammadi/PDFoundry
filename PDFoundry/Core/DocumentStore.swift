import Observation
import PDFKit
import UIKit

/// One PDF shown in the library: a document kept in PDFoundry, or a file opened where it lives.
nonisolated struct PDFItem: Identifiable, Hashable, Sendable {
    let url: URL
    let name: String
    let pageCount: Int
    let size: Int64
    let modified: Date
    let isLocked: Bool
    /// Where the file lives when it is not in PDFoundry's own folder, for example "iCloud Drive › Contracts".
    /// Nil for documents kept in PDFoundry.
    var location: String?
    /// The file is in iCloud and not on this device yet. It downloads when it is opened.
    var needsDownload = false

    var id: URL { url }

    /// Picked in the Files app for one job on the Tools tab: not opened here, not listed, never deleted or forgotten.
    var isPicked = false

    /// Opened in place from the Files app, not copied into PDFoundry.
    var isExternal: Bool { location != nil }

    var detail: String {
        if needsDownload { return "In iCloud" }
        let pages = isLocked ? "Locked" : (pageCount == 1 ? "1 page" : "\(pageCount) pages")
        return "\(pages) · \(ByteCountFormatter.string(fromByteCount: size, countStyle: .file))"
    }
}

/// Keeps a file from the Files app readable and writable for as long as this object is alive.
nonisolated final class FileAccess: Sendable {
    private let url: URL
    private let granted: Bool

    init(_ url: URL) {
        self.url = url
        granted = url.startAccessingSecurityScopedResource()
    }

    deinit {
        if granted { url.stopAccessingSecurityScopedResource() }
    }

    /// Runs `work` with access to `url`.
    static func with<T>(_ url: URL, _ work: () throws -> T) rethrows -> T {
        let access = FileAccess(url)
        defer { withExtendedLifetime(access) {} }
        return try work()
    }
}

enum LibrarySort: String, CaseIterable, Identifiable {
    case recent, name, size

    var id: String { rawValue }

    var title: String {
        switch self {
        case .recent: "Date Modified"
        case .name: "Name"
        case .size: "Size"
        }
    }
}

/// The library: every PDF in the app's Documents folder (also visible in the Files app), plus files that were
/// opened where they live (iCloud Drive, another folder) and are edited in place without being copied.
@Observable
final class DocumentStore {
    private(set) var items: [PDFItem] = []
    private(set) var isLoaded = false
    /// A document opened from outside the app, waiting to be shown by the library.
    var pendingOpen: URL?

    let directory: URL
    /// Remembers the files opened in place, as bookmarks that keep working after a restart.
    private let linksFile: URL
    /// Files under these folders are temporary hand-overs from other apps (Mail, Safari, the share sheet).
    /// They have no lasting home, so they are copied into the library instead of being opened in place.
    private let copyRoots: [URL]

    init(directory: URL = URL.documentsDirectory, linksFile: URL? = nil, copyRoots: [URL]? = nil) {
        self.directory = directory
        self.linksFile = linksFile ?? URL.applicationSupportDirectory.appending(path: "OpenedFiles.json")
        self.copyRoots = copyRoots ?? [URL(filePath: NSHomeDirectory())]
    }

    func start() async {
        seedWelcomeGuideIfNeeded()
        await refresh()
    }

    /// Counts the refreshes, so one that started earlier never replaces the list of one that started later.
    @ObservationIgnored private var refreshCount = 0

    func refresh() async {
        refreshCount += 1
        let turn = refreshCount
        let kept = await Self.scan(directory)
        let (linked, dead, renewed) = await Self.resolve(links)
        // Files that were deleted drop off the list. Only those exact bookmarks are removed: another file may
        // have been opened while this was working, and it must not be lost. A file that just can't be reached
        // right now (its app is signed out, a drive is unplugged) keeps its bookmark and comes back later.
        if !dead.isEmpty || !renewed.isEmpty {
            links = links.filter { !dead.contains($0.bookmark) }.map { link in
                renewed[link.bookmark].map { Link(bookmark: $0, added: link.added) } ?? link
            }
        }
        // A later refresh has started and has newer facts: the list is left to it.
        guard turn == refreshCount else { return }
        let own = Set(kept.map { $0.url.standardizedFileURL })
        items = kept + linked.filter { !own.contains($0.url.standardizedFileURL) }
        isLoaded = true
    }

    // MARK: Files opened in place

    fileprivate struct Link: Codable, Sendable {
        var bookmark: Data
        var added: Date
    }

    private var links: [Link] {
        get { (try? JSONDecoder().decode([Link].self, from: Data(contentsOf: linksFile))) ?? [] }
        set {
            try? FileManager.default.createDirectory(at: linksFile.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? JSONEncoder().encode(newValue).write(to: linksFile, options: .atomic)
        }
    }

    /// Is this a temporary hand-over that must be copied, not a file with a lasting home?
    private func mustCopy(_ url: URL) -> Bool {
        let path = url.standardizedFileURL.path
        return copyRoots.contains { path.hasPrefix($0.standardizedFileURL.path + "/") }
    }

    private func isInLibrary(_ url: URL) -> Bool {
        url.deletingLastPathComponent().standardizedFileURL == directory.standardizedFileURL
    }

    /// Opens files from the Files app. A file with a lasting home is opened where it is and listed with its
    /// location; nothing is copied. Temporary hand-overs are copied into the library. Returns where each file is.
    @discardableResult
    func open(_ urls: [URL]) async throws -> [URL] {
        var opened: [URL] = []
        let known = Set(await Self.resolve(links).0.map { $0.url.standardizedFileURL })
        var added: [Link] = []
        for url in urls {
            if isInLibrary(url) {
                opened.append(url)
            } else if mustCopy(url) {
                opened += try await importFiles([url])
            } else {
                try FileAccess.with(url) {
                    guard Self.isPDF(url) else { throw PDFoundryError.unreadable(url.deletingPathExtension().lastPathComponent) }
                    if !known.contains(url.standardizedFileURL) {
                        added.append(Link(bookmark: try url.bookmarkData(), added: .now))
                    }
                }
                opened.append(url)
            }
        }
        if !added.isEmpty { links += added } // read again here, so nothing saved meanwhile is overwritten
        await refresh()
        return opened
    }

    /// Takes files opened in place off the list. The files themselves are not touched.
    func forget(_ targets: [PDFItem]) async {
        let gone = Set(targets.filter(\.isExternal).map { $0.url.standardizedFileURL })
        guard !gone.isEmpty else { return }
        links = links.filter { link in
            var stale = false
            // A bookmark that can't be read right now is kept: refresh removes the ones that are really dead.
            guard let url = try? URL(resolvingBookmarkData: link.bookmark, bookmarkDataIsStale: &stale) else { return true }
            return !gone.contains(url.standardizedFileURL)
        }
        await refresh()
    }

    /// Copies a file opened in place into PDFoundry's own folder.
    @discardableResult
    func saveCopy(of item: PDFItem) async throws -> URL {
        let destination = uniqueURL(named: item.name)
        try FileAccess.with(item.url) { try FileManager.default.copyItem(at: item.url, to: destination) }
        await refresh()
        return destination
    }

    /// Describes a file picked in the Files app for a single job (a tool), without opening it here or adding it
    /// to the list. It is read through a file coordinator, so a file still in iCloud is downloaded first and its
    /// page count is right. It carries its location and `isPicked`, so it is never deleted or taken off the list.
    nonisolated static func pickedItem(_ url: URL) async -> PDFItem? {
        guard let data = await readCoordinated(url), data.prefix(1024).range(of: Data("%PDF-".utf8)) != nil else { return nil }
        let document = PDFDocument(data: data)
        guard let document else { return nil }
        let modified = FileAccess.with(url) { try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate }
        return PDFItem(
            url: url,
            name: url.deletingPathExtension().lastPathComponent,
            pageCount: document.pageCount,
            size: Int64(data.count),
            modified: modified ?? .now,
            isLocked: document.isLocked,
            location: location(of: url),
            isPicked: true
        )
    }

    private nonisolated static func isPDF(_ url: URL) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return url.pathExtension.lowercased() == "pdf" }
        defer { try? handle.close() }
        let head = (try? handle.read(upToCount: 1024)) ?? Data()
        // A file still in iCloud has no bytes here yet: trust its name.
        return head.isEmpty ? url.pathExtension.lowercased() == "pdf" : head.range(of: Data("%PDF-".utf8)) != nil
    }

    /// A short, readable place name for a file outside PDFoundry: "iCloud Drive › Contracts".
    nonisolated static func location(of url: URL) -> String {
        let folder = url.deletingLastPathComponent()
        let parts = folder.pathComponents
        var root = "Files"
        var rest: [String] = []
        if let index = parts.firstIndex(where: { $0.hasPrefix("com~apple~CloudDocs") }) {
            root = "iCloud Drive"
            rest = Array(parts[(index + 1)...])
        } else if let index = parts.firstIndex(of: "Mobile Documents") {
            root = "iCloud Drive"
            rest = Array(parts[(index + 1)...]).filter { $0 != "Documents" && !$0.contains("~") }
        } else if let index = parts.firstIndex(of: "File Provider Storage") {
            root = "On My \(UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone")"
            rest = Array(parts[(index + 1)...])
        } else if let last = parts.last, last != "/" {
            rest = [last]
        }
        guard let last = rest.last else { return root }
        return "\(root) › \(last)"
    }

    func sorted(_ sort: LibrarySort, matching query: String) -> [PDFItem] {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        let filtered = trimmed.isEmpty ? items : items.filter { $0.name.localizedCaseInsensitiveContains(trimmed) }
        switch sort {
        case .recent: return filtered.sorted { $0.modified > $1.modified }
        case .name: return filtered.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        case .size: return filtered.sorted { $0.size > $1.size }
        }
    }

    // MARK: Adding

    /// Brings files into the library and returns where each one is.
    /// A file whose contents are already in the library is not copied again: its existing document is returned.
    @discardableResult
    func importFiles(_ urls: [URL]) async throws -> [URL] {
        var imported: [URL] = []
        for url in urls {
            if url.deletingLastPathComponent().standardizedFileURL == directory.standardizedFileURL {
                imported.append(url)
                continue
            }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            if let existing = existingCopy(of: url) {
                if !imported.contains(existing) { imported.append(existing) }
                continue
            }
            let destination = uniqueURL(named: url.deletingPathExtension().lastPathComponent)
            try FileManager.default.copyItem(at: url, to: destination)
            imported.append(destination)
        }
        await refresh()
        return imported
    }

    /// A document already in the library with exactly the same contents as `url`, if there is one.
    /// Files handed over by other apps arrive as temporary copies, so contents are the only thing to compare.
    func existingCopy(of url: URL) -> URL? {
        let manager = FileManager.default
        guard let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize,
              let files = try? manager.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey])
        else { return nil }
        return files.first { candidate in
            candidate.pathExtension.lowercased() == "pdf"
                && (try? candidate.resourceValues(forKeys: [.fileSizeKey]))?.fileSize == size
                && manager.contentsEqual(atPath: candidate.path, andPath: url.path)
        }
    }

    /// Handles a PDF handed to the app by Files, Mail, Safari or the share sheet.
    func openExternal(_ url: URL) {
        Task {
            if let first = try? await open([url]).first {
                pendingOpen = first
            }
        }
    }

    /// Writes `document` as a new file in the library. With a password, the file is encrypted with it.
    @discardableResult
    func save(_ document: PDFDocument, named name: String, password: String? = nil, refreshing: Bool = true) async throws -> URL {
        let destination = uniqueURL(named: name)
        guard document.write(to: destination, withOptions: PDFOperations.writeOptions(password: password)) else {
            throw PDFoundryError.writeFailed
        }
        if refreshing { await refresh() }
        return destination
    }

    /// Keeps a compressed copy in the library as "<name> (compressed)". The source is not touched or listed.
    func saveCompressed(_ data: Data, named name: String) async throws -> PDFItem {
        let destination = uniqueURL(named: name + " (compressed)")
        do {
            try data.write(to: destination, options: .atomic)
        } catch {
            throw PDFoundryError.writeFailed
        }
        let document = PDFDocument(data: data)
        await refresh()
        return item(for: destination, pageCount: document?.pageCount ?? 0, isLocked: document?.isLocked ?? false)
    }

    /// The listed item for a file just written to the library.
    private func item(for url: URL, pageCount: Int, isLocked: Bool) -> PDFItem {
        items.first { $0.url.standardizedFileURL == url.standardizedFileURL } ?? PDFItem(
            url: url,
            name: url.deletingPathExtension().lastPathComponent,
            pageCount: pageCount,
            size: 0,
            modified: .now,
            isLocked: isLocked
        )
    }

    /// Splits the file at `url` into new documents in the library, one per entry of `parts` (zero-based page
    /// lists), named "<name> (part 1)", "(part 2)"… Like Extract, the source is only read, never written or listed.
    /// A protected source needs its password (with full access), and every part is protected with it too.
    /// The work is done away from the main thread. If a part can't be made, the parts already written are removed.
    func split(_ url: URL, into parts: [[Int]], password: String?) async throws -> [PDFItem] {
        let name = url.deletingPathExtension().lastPathComponent
        let destinations = uniqueURLs(named: parts.indices.map { "\(name) (part \($0 + 1))" })
        let data = await Self.readCoordinated(url)
        do {
            let result = try await Self.writeParts(of: data, named: name, password: password, parts: parts, to: destinations)
            await refresh()
            return zip(destinations, result.pageCounts).map { item(for: $0, pageCount: $1, isLocked: result.isProtected) }
        } catch {
            await refresh()
            throw error
        }
    }

    /// Free names for several new documents at once (none of them written yet).
    private func uniqueURLs(named names: [String]) -> [URL] {
        var taken: Set<String> = []
        return names.map { name in
            var url = uniqueURL(named: name)
            var counter = 2
            while taken.contains(url.lastPathComponent) {
                url = uniqueURL(named: "\(name) \(counter)")
                counter += 1
            }
            taken.insert(url.lastPathComponent)
            return url
        }
    }

    /// Reads a source with full access and writes one new document per page list, keeping the source's password.
    /// Runs away from the main thread; every PDFDocument here is made and used on this one task.
    @concurrent
    private nonisolated static func writeParts(of data: Data?, named name: String, password: String?, parts: [[Int]],
                                               to destinations: [URL]) async throws -> (pageCounts: [Int], isProtected: Bool) {
        let source = try PDFOperations.fullAccess(data, password: password, name: name)
        let keep = source.isEncrypted ? password : nil
        var written: [URL] = []
        var counts: [Int] = []
        do {
            for (pages, destination) in zip(parts, destinations) {
                let document = try PDFOperations.extract(pages, from: source)
                guard document.write(to: destination, withOptions: PDFOperations.writeOptions(password: keep)) else {
                    throw PDFoundryError.writeFailed
                }
                written.append(destination)
                counts.append(document.pageCount)
            }
        } catch {
            for url in written { try? FileManager.default.removeItem(at: url) }
            throw error
        }
        return (counts, keep != nil)
    }

    @discardableResult
    func create(from images: [UIImage], named name: String) async throws -> URL {
        try await save(PDFOperations.document(from: images), named: name)
    }

    @discardableResult
    func merge(_ sources: [PDFItem], named name: String, deleteOriginals: Bool) async throws -> URL {
        // Read through a file coordinator into memory, so files in iCloud are downloaded first and the sources are
        // not needed while the merged document is written.
        var contents: [(name: String, data: Data?)] = []
        for source in sources {
            contents.append((source.name, await Self.readCoordinated(source.url)))
        }
        let merged = try PDFOperations.merge(contents)
        let destination = uniqueURL(named: name)
        guard merged.write(to: destination) else { throw PDFoundryError.writeFailed }
        if deleteOriginals {
            // Only documents kept in PDFoundry are deleted. Files opened in place are just taken off the list.
            // Files picked in Files for this merge are left alone, even when the same file is also on the list.
            let kept = sources.filter { !$0.isPicked }
            for source in kept where !source.isExternal { try? FileManager.default.removeItem(at: source.url) }
            await forget(kept)
        }
        await refresh()
        return destination
    }

    /// Copies the pages at `pages` (zero-based, kept in document order) of the file at `url` into a new document in
    /// the library, named "<name> (pages)". The source is only read, never written: it may be a file opened in
    /// place, whose every change would otherwise land in the real file. It is not added to the library either.
    /// A protected source needs its password (with full access), and the new document keeps it.
    func extractPages(from url: URL, pages: IndexSet, password: String? = nil) async throws -> PDFItem {
        let name = url.deletingPathExtension().lastPathComponent
        let destination = uniqueURL(named: name + " (pages)")
        let data = await Self.readCoordinated(url)
        let result = try await Self.writeParts(of: data, named: name, password: password, parts: [Array(pages)], to: [destination])
        await refresh()
        return item(for: destination, pageCount: result.pageCounts[0], isLocked: result.isProtected)
    }

    /// Reads a file's bytes through a file coordinator (so a file in iCloud is downloaded first), with access to it
    /// if it was picked in Files.
    @concurrent
    nonisolated static func readCoordinated(_ url: URL) async -> Data? {
        FileAccess.with(url) {
            var data: Data?
            var error: NSError?
            NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &error) { data = try? Data(contentsOf: $0) }
            return data
        }
    }

    // MARK: Changing

    // MARK: Open readers

    private struct OpenReader {
        weak var model: ReaderModel?
    }

    @ObservationIgnored private var readers: [OpenReader] = []

    /// The reader showing a file tells the store, so a tool that changes the file (Protect) can tell it back.
    /// Held weakly: a closed reader drops off by itself.
    func attach(_ reader: ReaderModel) {
        readers.removeAll { $0.model == nil || $0.model === reader }
        readers.append(OpenReader(model: reader))
    }

    private func openReader(for url: URL) -> ReaderModel? {
        let target = url.standardizedFileURL
        return readers.lazy.compactMap(\.model).first { $0.url.standardizedFileURL == target }
    }

    /// Protects the file at `url` with `password`, in place: the file itself is overwritten with the encrypted
    /// version (a file opened in place stays where it is; nothing is copied). An already protected file is refused.
    /// If the file is open in a reader, the reader saves its last changes first and takes the password, so its next
    /// save keeps the file protected.
    func protect(_ url: URL, password: String) async throws {
        let name = url.deletingPathExtension().lastPathComponent
        guard !password.isEmpty else { throw PDFoundryError.wrongPassword }
        let reader = openReader(for: url)
        reader?.syncFields()
        try await Self.protectFile(at: url, named: name, password: password, atomic: isInLibrary(url))
        reader?.adoptProtection(password: password)
        await refresh()
    }

    @concurrent
    private nonisolated static func protectFile(at url: URL, named name: String, password: String, atomic: Bool) async throws {
        guard let data = await readCoordinated(url), let document = PDFDocument(data: data) else {
            throw PDFoundryError.unreadable(name)
        }
        guard !document.isEncrypted else { throw PDFoundryError.alreadyProtected(name) }
        let written: Bool = FileAccess.with(url) {
            var written = false
            var error: NSError?
            // Coordinated, as the reader's saves are, so iCloud Drive and other apps see one complete change.
            NSFileCoordinator().coordinate(writingItemAt: url, options: .forReplacing, error: &error) { target in
                written = PDFOperations.writeInPlace(document, to: target, password: password, atomic: atomic)
            }
            return written
        }
        guard written else { throw PDFoundryError.writeFailed }
    }

    func rename(_ item: PDFItem, to newName: String) async throws {
        let clean = Self.sanitized(newName)
        guard !clean.isEmpty, clean != item.name else { return }
        try FileManager.default.moveItem(at: item.url, to: uniqueURL(named: clean))
        await refresh()
    }

    func duplicate(_ item: PDFItem) async throws {
        try FileManager.default.copyItem(at: item.url, to: uniqueURL(named: item.name + " copy"))
        await refresh()
    }

    /// Deletes documents kept in PDFoundry. Files opened in place are only taken off the list.
    func delete(_ targets: [PDFItem]) async {
        for item in targets where !item.isExternal { try? FileManager.default.removeItem(at: item.url) }
        await forget(targets)
        await refresh()
    }

    // MARK: Helpers

    func uniqueURL(named rawName: String) -> URL {
        let base = Self.sanitized(rawName).isEmpty ? "Untitled" : Self.sanitized(rawName)
        var candidate = directory.appending(path: base + ".pdf")
        var counter = 2
        while FileManager.default.fileExists(atPath: candidate.path) {
            candidate = directory.appending(path: "\(base) \(counter).pdf")
            counter += 1
        }
        return candidate
    }

    static func sanitized(_ name: String) -> String {
        var clean = name.replacingOccurrences(of: "/", with: "-").replacingOccurrences(of: ":", with: "-")
        clean = clean.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.lowercased().hasSuffix(".pdf") { clean = String(clean.dropLast(4)) }
        while clean.hasPrefix(".") { clean.removeFirst() }
        return String(clean.prefix(120))
    }

    private func seedWelcomeGuideIfNeeded() {
        let defaults = UserDefaults.standard
        guard !defaults.bool(forKey: SettingsKey.didSeedWelcome) else { return }
        defaults.set(true, forKey: SettingsKey.didSeedWelcome)
        try? PDFOperations.welcomeGuide().write(to: uniqueURL(named: "Welcome to PDFoundry"), options: .atomic)
    }

    @concurrent
    private nonisolated static func scan(_ directory: URL) async -> [PDFItem] {
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey]
        let urls = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles])) ?? []
        return urls.filter { $0.pathExtension.lowercased() == "pdf" }.map { url in
            let values = try? url.resourceValues(forKeys: Set(keys))
            let document = PDFDocument(url: url)
            return PDFItem(
                url: url,
                name: url.deletingPathExtension().lastPathComponent,
                pageCount: document?.pageCount ?? 0,
                size: Int64(values?.fileSize ?? 0),
                modified: values?.contentModificationDate ?? .distantPast,
                isLocked: document?.isLocked ?? false
            )
        }
    }
}

extension DocumentStore {
    /// The file is certainly gone (deleted), not just out of reach for now.
    private nonisolated static func isGone(_ error: Error) -> Bool {
        let error = error as NSError
        guard error.domain == NSCocoaErrorDomain else { return false }
        return error.code == NSFileNoSuchFileError || error.code == NSFileReadNoSuchFileError
    }

    /// Turns the saved bookmarks back into files. Returns the items to show, the bookmarks whose file is gone
    /// (deleted) so they can be dropped, and fresh bookmarks for the ones the system reported as stale.
    /// A file that can't be reached right now is left out of the items but keeps its bookmark.
    @concurrent
    fileprivate nonisolated static func resolve(_ links: [Link]) async -> ([PDFItem], Set<Data>, [Data: Data]) {
        var items: [PDFItem] = []
        var dead: Set<Data> = []
        var renewed: [Data: Data] = [:]
        var seen: Set<URL> = []
        let keys: Set<URLResourceKey> = [.fileSizeKey, .contentModificationDateKey, .ubiquitousItemDownloadingStatusKey]
        for link in links {
            var stale = false
            let url: URL
            do {
                url = try URL(resolvingBookmarkData: link.bookmark, bookmarkDataIsStale: &stale)
            } catch {
                if isGone(error) { dead.insert(link.bookmark) }
                continue
            }
            let kept: Bool = FileAccess.with(url) {
                do {
                    guard try url.checkResourceIsReachable() else { return true }
                } catch {
                    return !isGone(error)
                }
                // The same file listed twice: the second bookmark is dropped.
                guard seen.insert(url.standardizedFileURL).inserted else { return false }
                if stale, let fresh = try? url.bookmarkData() { renewed[link.bookmark] = fresh }
                let values = try? url.resourceValues(forKeys: keys)
                let waiting = values?.ubiquitousItemDownloadingStatus.map { $0 != .current } ?? false
                let document = waiting ? nil : PDFDocument(url: url)
                items.append(PDFItem(
                    url: url,
                    name: url.deletingPathExtension().lastPathComponent,
                    pageCount: document?.pageCount ?? 0,
                    size: Int64(values?.fileSize ?? 0),
                    modified: max(values?.contentModificationDate ?? .distantPast, link.added),
                    isLocked: document?.isLocked ?? false,
                    location: location(of: url),
                    needsDownload: waiting
                ))
                return true
            }
            if !kept { dead.insert(link.bookmark) }
        }
        return (items, dead, renewed)
    }
}

/// Renders and caches first-page thumbnails for the library grid.
enum Thumbnailer {
    private static let cache = NSCache<NSString, UIImage>()

    static func thumbnail(for item: PDFItem, scale: CGFloat) async -> UIImage? {
        let key = "\(item.url.path)|\(item.modified.timeIntervalSince1970)" as NSString
        if let cached = cache.object(forKey: key) { return cached }
        guard let image = await generate(item.url, scale: scale) else { return nil }
        cache.setObject(image, forKey: key)
        return image
    }

    @concurrent
    private nonisolated static func generate(_ url: URL, scale: CGFloat) async -> UIImage? {
        // Each call opens its own document, so rendering off the main thread is safe.
        FileAccess.with(url) {
            guard let page = PDFDocument(url: url)?.page(at: 0) else { return nil }
            return page.thumbnail(of: CGSize(width: 240 * scale, height: 320 * scale), for: .cropBox)
        }
    }
}
