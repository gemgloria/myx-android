import Foundation

struct OCRLine: Hashable, Sendable {
    var text: String
    var confidence: Float = 1
    // Coordinates originate at the top left, normalized to the image.
    var x: Double = 0
    var y: Double = 0
    var width: Double = 0
    var height: Double = 0
    var centerX: Double { x + width / 2 }
}

struct GridRegion: Sendable {
    var left: Double
    var top: Double
    var right: Double
    var bottom: Double
}

struct ImportCandidate: Identifiable, Sendable {
    var course: Course
    var confidence: Float
    var warnings: [String]
    var selected = true
    var id: UUID { course.id }
}

struct ImportResult: Sendable {
    var candidates: [ImportCandidate]
    var suggestedSemesterName: String?
    var warnings: [String]
    var duplicateCount: Int
}

enum TimetableParser {
    static func detectGrid(in lines: [OCRLine]) -> GridRegion? {
        let suffixes = ["一", "二", "三", "四", "五", "六", "日"]
        let headers = lines.compactMap { line -> (Int, OCRLine)? in
            let value = WeekExpression.normalize(line.text)
            for (index, suffix) in suffixes.enumerated() {
                if value == "星期" + suffix || value == "周" + suffix ||
                    (index == 6 && ["星期天", "周天"].contains(value)) { return (index + 1, line) }
            }
            return nil
        }
        // A full seven-column header is necessary: avoid silently guessing weekdays.
        for (_, anchor) in headers {
            let row = headers.filter { abs($0.1.y - anchor.y) < max(0.02, anchor.height * 2) }
            var map: [Int: OCRLine] = [:]
            for (day, line) in row { map[day] = line }
            guard (1...7).allSatisfy({ map[$0] != nil }),
                  let first = map[1], let last = map[7] else { continue }
            let step = (last.centerX - first.centerX) / 6
            guard step > 0.03 else { continue }
            let regular = (1...7).allSatisfy { day in
                abs(map[day]!.centerX - (first.centerX + Double(day - 1) * step)) < step * 0.3
            }
            guard regular else { continue }
            let top = map.values.map { $0.y + $0.height }.max()! + 0.002
            let footer = lines.filter { $0.y > top && $0.text.contains("备注") }.map(\.y).min() ?? 1
            return GridRegion(left: max(0, first.centerX - step / 2), top: top,
                              right: min(1, last.centerX + step / 2), bottom: footer)
        }
        return nil
    }

    static func semesterName(in lines: [OCRLine]) -> String? {
        let text = lines.map { WeekExpression.normalize($0.text) }.joined(separator: " ")
        let pattern = #"(20[0-9]{2})-(20[0-9]{2})-(1|2)"#
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) else { return nil }
        let ns = text as NSString
        return "\(ns.substring(with: match.range(at: 1)))–\(ns.substring(with: match.range(at: 2))) 第\(ns.substring(with: match.range(at: 3)))学期"
    }

    static func parseColumn(_ rawLines: [OCRLine], weekday: Int) -> (candidates: [ImportCandidate], warnings: [String]) {
        let lines = rawLines.sorted { abs($0.y - $1.y) < 0.001 ? $0.x < $1.x : $0.y < $1.y }
            .filter { !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        let texts = lines.map { WeekExpression.normalize($0.text)
            .replacingOccurrences(of: "【", with: "[").replacingOccurrences(of: "】", with: "]") }
        var offsets: [Int] = []
        var buffer = ""
        for text in texts { offsets.append((buffer as NSString).length); buffer += text }
        // Hyphens may enumerate a four-period block: [01-02-03-04节].
        let pattern = #"([0-9][0-9,\-]*(?:\((?:单|双)\))?)(?:\(周\)|周)(单|双)?\[([0-9][0-9,\-]*)节\]"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return ([], []) }
        let ns = buffer as NSString
        let matches = regex.matches(in: buffer, range: NSRange(location: 0, length: ns.length))
        var candidates: [ImportCandidate] = []
        var warnings: [String] = []
        var previousEnd = -1

        func lineIndex(at position: Int) -> Int {
            offsets.lastIndex(where: { $0 <= position }) ?? 0
        }
        for (matchIndex, match) in matches.enumerated() {
            let startIndex = lineIndex(at: match.range.location)
            let endIndex = lineIndex(at: match.range.location + match.range.length - 1)
            let nextStart = matchIndex + 1 < matches.count ? lineIndex(at: matches[matchIndex + 1].range.location) : lines.count
            var prefix = previousEnd + 1 < startIndex ? Array(texts[(previousEnd + 1)..<startIndex]) : []
            // Remove the previous course's room and dashed table separators.
            while let first = prefix.first, isRoom(first) || isNoise(first) { prefix.removeFirst() }
            prefix = prefix.filter { !isNoise($0) }
            var roomParts: [String] = []
            var roomEndIndex = endIndex
            if endIndex + 1 < nextStart {
                for index in (endIndex + 1)..<nextStart {
                    let line = texts[index]
                    if isNoise(line) { if !roomParts.isEmpty { break }; continue }
                    if roomParts.isEmpty {
                        if isRoom(line) { roomParts.append(line); roomEndIndex = index } else { break }
                    } else if isRoom(line) || line.contains("实验室") || line == "室" || line.contains("验室") || line == "节)" || line.contains("楼物理") {
                        roomParts.append(line)
                        roomEndIndex = index
                    } else { break }
                }
            }
            // Wrapped room tails must also be removed from the next prefix.
            if !roomParts.isEmpty, prefix.first?.contains("室") == true { prefix.removeFirst() }
            let fields = splitNameAndTeacher(prefix)
            let raw = Array(texts[max(0, previousEnd + 1)..<min(lines.count, endIndex + 1 + roomParts.count)]).joined(separator: "\n")
            previousEnd = roomEndIndex
            guard !fields.name.isEmpty else {
                warnings.append("\(AcademicCalendar.weekdayNames[weekday - 1])有一段课程名称未识别完整，请核对原图。")
                continue
            }
            do {
                var weekInput = ns.substring(with: match.range(at: 1))
                if match.range(at: 2).location != NSNotFound { weekInput += ns.substring(with: match.range(at: 2)) }
                let weeks = try WeekExpression.parse(weekInput)
                let periodRanges = try parsePeriods(ns.substring(with: match.range(at: 3)))
                let confidence = lines[max(0, startIndex - prefix.count)...endIndex].map(\.confidence).min() ?? 0
                var issues: [String] = []
                if confidence < 0.82 { issues.append("文字清晰度较低，请核对") }
                if fields.teacher.isEmpty { issues.append("教师未识别，可手动补充") }
                if roomParts.isEmpty { issues.append("教室未识别，可手动补充") }
                var title = fields.name
                var note = ""
                if title.hasSuffix("P") || title.hasSuffix("O") {
                    let marker = title.removeLast()
                    note = marker == "P" ? "截图标记 P：部分调课，请核对。" : "截图标记 O：整体调课，请核对。"
                    issues.append(note)
                }
                for range in periodRanges {
                    let course = Course(name: title, teacher: fields.teacher,
                                        location: roomParts.joined(), weekday: weekday,
                                        startPeriod: range.lowerBound, endPeriod: range.upperBound,
                                        weeks: weeks, color: .stable(for: title), note: note, sourceText: raw)
                    candidates.append(.init(course: course, confidence: confidence, warnings: issues))
                }
            } catch {
                warnings.append("\(AcademicCalendar.weekdayNames[weekday - 1])的“\(fields.name)”周次或节次无法解析，请手动补充。")
            }
        }
        return (candidates, warnings)
    }

    static func deduplicate(_ input: [ImportCandidate]) -> (candidates: [ImportCandidate], duplicateCount: Int) {
        var seen = Set<String>()
        var candidates: [ImportCandidate] = []
        for candidate in input {
            if seen.insert(candidate.course.fingerprint).inserted { candidates.append(candidate) }
        }
        return (candidates, input.count - candidates.count)
    }
    static func parsePeriods(_ text: String) throws -> [ClosedRange<Int>] {
        let parts = text.split(separator: ",", omittingEmptySubsequences: false)
        var periods = Set<Int>()
        for part in parts {
            let values = part.split(separator: "-", omittingEmptySubsequences: false).compactMap { Int($0) }
            guard !values.isEmpty, values.count == part.split(separator: "-", omittingEmptySubsequences: false).count,
                  let start = values.first, let end = values.last, start >= 1, end >= start, end <= 16,
                  values == values.sorted() else { throw ScheduleError.invalid("节次无法识别。") }
            periods.formUnion(start...end)
        }
        let sorted = periods.sorted()
        guard let first = sorted.first else { throw ScheduleError.invalid("节次为空。") }
        var ranges: [ClosedRange<Int>] = []
        var start = first, end = first
        for period in sorted.dropFirst() {
            if period == end + 1 { end = period }
            else { ranges.append(start...end); start = period; end = period }
        }
        ranges.append(start...end)
        return ranges
    }
    private static func isNoise(_ text: String) -> Bool {
        text.isEmpty || text.allSatisfy { "-_—–=·".contains($0) } ||
        text.hasPrefix("星期") || text == "节" || text.hasPrefix("备注")
    }
    private static func isRoom(_ text: String) -> Bool {
        let prefixes = ["教一", "教二", "教三", "教四", "教五", "东教", "西教", "南教", "北教", "教1", "教2", "教3", "实训楼", "实验楼", "博学", "综合楼"]
        return prefixes.contains { text.hasPrefix($0) }
    }
    private static func splitNameAndTeacher(_ input: [String]) -> (name: String, teacher: String) {
        guard !input.isEmpty else { return ("", "") }
        guard input.count > 1 else { return (input[0], "") }
        var teacherStart = input.count - 1
        let last = input[teacherStart]
        let looksLikeName = (2...4).contains(last.count) && last.unicodeScalars.allSatisfy { (0x4E00...0x9FFF).contains(Int($0.value)) }
        if !looksLikeName && !last.contains(",") { return (input.joined(), "") }
        while teacherStart > 1 && (input[teacherStart - 1].contains(",") || input[teacherStart - 1].hasSuffix("、")) {
            teacherStart -= 1
        }
        return (input[..<teacherStart].joined(), input[teacherStart...].joined())
    }
}
