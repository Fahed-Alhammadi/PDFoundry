import Foundation

/// Why a page-range text can't be used. The messages are the same on every platform.
nonisolated enum RangeError: LocalizedError, Equatable {
    case empty
    case missingPage(Int, pageCount: Int)
    case backwards
    case unreadable

    var errorDescription: String? {
        switch self {
        case .empty: "Enter page ranges, such as 1-3, 4-6."
        case .missingPage(let page, let count): "Page \(page) doesn't exist. This PDF has \(count == 1 ? "1 page" : "\(count) pages")."
        case .backwards: "Write ranges from low to high, such as 3-5."
        case .unreadable: "Use numbers, dashes and commas, such as 1-3, 4-6."
        }
    }
}

/// Page ranges for Split PDF. Pages are counted from 1 in the text and from 0 in the result.
nonisolated enum PageRanges {
    /// Reads text such as `1-3, 4-6, 9` into one list of pages per comma-separated item. Spaces are ignored and
    /// ranges may overlap. Empty items (a trailing comma) are skipped.
    static func parse(_ text: String, pageCount: Int) -> Result<[[Int]], RangeError> {
        let compact = text.filter { !$0.isWhitespace }
        let items = compact.split(separator: ",", omittingEmptySubsequences: true)
        guard !items.isEmpty else { return .failure(.empty) }
        var parts: [[Int]] = []
        for item in items {
            let bounds = item.split(separator: "-", omittingEmptySubsequences: false)
            guard (1...2).contains(bounds.count),
                  bounds.allSatisfy({ !$0.isEmpty && $0.allSatisfy(\.isASCII) && $0.allSatisfy(\.isNumber) }) else {
                return .failure(.unreadable)
            }
            // Very long digit strings don't fit an Int: they are certainly past the last page.
            let numbers = bounds.map { Int($0) ?? Int.max }
            for number in numbers where number < 1 || number > pageCount {
                return .failure(.missingPage(number, pageCount: pageCount))
            }
            let low = numbers[0], high = numbers.last!
            guard low <= high else { return .failure(.backwards) }
            parts.append(Array((low - 1)...(high - 1)))
        }
        return .success(parts)
    }

    /// Consecutive chunks of `n` pages; the last one may be shorter. "Every page" is `every(1, …)`.
    static func every(_ n: Int, pageCount: Int) -> [[Int]] {
        let size = max(1, n)
        return stride(from: 0, to: pageCount, by: size).map { Array($0..<min($0 + size, pageCount)) }
    }
}
