import Foundation

enum WeekExpression {
    static func normalize(_ input: String) -> String {
        var output = input.applyingTransform(.fullwidthToHalfwidth, reverse: false) ?? input
        for character in ["—", "–", "－", "~", "～", "至"] { output = output.replacingOccurrences(of: character, with: "-") }
        for character in ["，", "、", ";", "；"] { output = output.replacingOccurrences(of: character, with: ",") }
        return output.replacingOccurrences(of: " ", with: "")
            .replacingOccurrences(of: "\n", with: "").replacingOccurrences(of: "\t", with: "")
    }

    static func parse(_ input: String, maxWeek: Int = 53) throws -> [Int] {
        let normalized = normalize(input)
        let odd = normalized.contains("单")
        let even = normalized.contains("双")
        guard !(odd && even) else { throw ScheduleError.invalid("单双周不能同时选择。") }
        var text = normalized
        for token in ["(单周)", "(双周)", "(单)", "(双)", "(周)", "单周", "双周", "单", "双", "第", "周"] {
            text = text.replacingOccurrences(of: token, with: "")
        }
        guard !text.isEmpty else { throw ScheduleError.invalid("请填写周次，例如 1-4,6-14。") }
        var weeks = Set<Int>()
        for part in text.split(separator: ",", omittingEmptySubsequences: false) {
            let numbers = part.split(separator: "-", omittingEmptySubsequences: false)
            guard (1...2).contains(numbers.count), let start = Int(numbers[0]),
                  let end = Int(numbers.last!), start >= 1, end >= start, end <= maxWeek else {
                throw ScheduleError.invalid("周次格式有误，请使用 1-4,6-14 这样的格式，且不超过 \(maxWeek) 周。")
            }
            for week in start...end where (!odd || week % 2 == 1) && (!even || week % 2 == 0) {
                weeks.insert(week)
            }
        }
        guard !weeks.isEmpty else { throw ScheduleError.invalid("所选范围内没有符合条件的周次。") }
        return weeks.sorted()
    }

    static func format(_ weeks: [Int]) -> String {
        let values = Array(Set(weeks)).sorted()
        guard let first = values.first else { return "" }
        var result: [String] = []
        var start = first
        var end = first
        for week in values.dropFirst() {
            if week == end + 1 { end = week }
            else {
                result.append(start == end ? "\(start)" : "\(start)-\(end)")
                start = week; end = week
            }
        }
        result.append(start == end ? "\(start)" : "\(start)-\(end)")
        return result.joined(separator: ",")
    }
}
