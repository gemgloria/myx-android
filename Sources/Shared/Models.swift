import Foundation

enum CourseColor: String, Codable, CaseIterable, Sendable {
    case violet, teal, blue, amber, rose, indigo

    static func stable(for name: String) -> Self {
        let value = name.utf8.reduce(UInt64(5381)) { ($0 &* 33) &+ UInt64($1) }
        return allCases[Int(value % UInt64(allCases.count))]
    }
}

struct ClassPeriod: Codable, Hashable, Identifiable, Sendable {
    var id: Int
    var startMinute: Int
    var endMinute: Int

    var startText: String { Self.format(startMinute) }
    var endText: String { Self.format(endMinute) }
    static func format(_ minutes: Int) -> String {
        String(format: "%02d:%02d", minutes / 60, minutes % 60)
    }

    static let defaults: [Self] = [
        .init(id: 1, startMinute: 480, endMinute: 525),
        .init(id: 2, startMinute: 530, endMinute: 575),
        .init(id: 3, startMinute: 595, endMinute: 640),
        .init(id: 4, startMinute: 645, endMinute: 690),
        .init(id: 5, startMinute: 840, endMinute: 885),
        .init(id: 6, startMinute: 890, endMinute: 935),
        .init(id: 7, startMinute: 945, endMinute: 990),
        .init(id: 8, startMinute: 995, endMinute: 1040),
        .init(id: 9, startMinute: 1110, endMinute: 1155),
        .init(id: 10, startMinute: 1160, endMinute: 1205),
        .init(id: 11, startMinute: 1210, endMinute: 1255),
        .init(id: 12, startMinute: 1260, endMinute: 1305)
    ]
}

struct Course: Codable, Hashable, Identifiable, Sendable {
    var id = UUID()
    var name: String
    var teacher = ""
    var location = ""
    var weekday: Int
    var startPeriod: Int
    var endPeriod: Int
    var weeks: [Int]
    var color: CourseColor = .violet
    var note = ""
    var sourceText = ""

    var weekText: String { WeekExpression.format(weeks) }
    var periodText: String {
        startPeriod == endPeriod ? "第\(startPeriod)节" : "第\(startPeriod)–\(endPeriod)节"
    }
    var fingerprint: String {
        [name.trimmingCharacters(in: .whitespacesAndNewlines), teacher, location,
         String(weekday), String(startPeriod), String(endPeriod),
         weeks.sorted().map(String.init).joined(separator: ",")].joined(separator: "|")
    }
    func isActive(week: Int) -> Bool { weeks.contains(week) }

    func validate(maxWeeks: Int, periodCount: Int) throws {
        guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw ScheduleError.invalid("请填写课程名称。")
        }
        guard (1...7).contains(weekday), startPeriod >= 1,
              endPeriod >= startPeriod, endPeriod <= periodCount else {
            throw ScheduleError.invalid("请检查星期和上课节次。")
        }
        guard !weeks.isEmpty, Set(weeks).count == weeks.count,
              weeks.allSatisfy({ (1...maxWeeks).contains($0) }) else {
            throw ScheduleError.invalid("周次应在第 1–\(maxWeeks) 周之间。")
        }
    }
}

struct Semester: Codable, Hashable, Identifiable, Sendable {
    var id = UUID()
    var name: String
    // A date-only string avoids timezone drift in semester boundaries.
    var firstMonday: String
    var weekCount = 20
    var periods = ClassPeriod.defaults
    var courses: [Course] = []

    func validate() throws {
        guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              (1...53).contains(weekCount),
              let day = AcademicCalendar.date(from: firstMonday),
              AcademicCalendar.weekday(day) == 1 else {
            throw ScheduleError.invalid("请检查学期名称、周数和第 1 周的周一。")
        }
        guard (1...16).contains(periods.count),
              periods.map(\.id) == Array(1...periods.count) else {
            throw ScheduleError.invalid("节次必须从第 1 节开始连续排列。")
        }
        for (index, period) in periods.enumerated() {
            guard period.startMinute >= 0, period.endMinute < 1440,
                  period.startMinute < period.endMinute,
                  index == 0 || periods[index - 1].endMinute <= period.startMinute else {
                throw ScheduleError.invalid("上课时间不能倒置或与上一节重叠。")
            }
        }
        guard Set(courses.map(\.id)).count == courses.count else {
            throw ScheduleError.invalid("课程标识重复，无法导入此备份。")
        }
        for course in courses { try course.validate(maxWeeks: weekCount, periodCount: periods.count) }
    }

    static func empty(now: Date = .now) -> Self {
        let year = AcademicCalendar.calendar.component(.year, from: now)
        let month = AcademicCalendar.calendar.component(.month, from: now)
        let title = month >= 8 ? "\(year)–\(year + 1) 第1学期" : "\(year - 1)–\(year) 第2学期"
        return .init(name: title, firstMonday: AcademicCalendar.string(from: AcademicCalendar.monday(of: now)))
    }
}

struct Preferences: Codable, Hashable, Sendable {
    var showWeekends = true
    var showInactiveCourses = false
    var appearance = "system"
}

struct ScheduleState: Codable, Hashable, Sendable {
    var schemaVersion = 1
    var selectedSemesterID: UUID
    var semesters: [Semester]
    var preferences = Preferences()

    var selectedSemester: Semester? { semesters.first { $0.id == selectedSemesterID } }

    static func empty() -> Self {
        let semester = Semester.empty()
        return .init(selectedSemesterID: semester.id, semesters: [semester])
    }
    func validate() throws {
        guard schemaVersion == 1, !semesters.isEmpty, selectedSemester != nil,
              Set(semesters.map(\.id)).count == semesters.count else {
            throw ScheduleError.invalid("备份格式不兼容，或未包含有效学期。")
        }
        for semester in semesters { try semester.validate() }
    }
}

enum ScheduleError: LocalizedError {
    case invalid(String)
    case unreadable
    case noGrid
    case noCourses
    var errorDescription: String? {
        switch self {
        case .invalid(let text): return text
        case .unreadable: return "无法读取这张图片，请选择原始课表截图。"
        case .noGrid: return "未找到完整的星期表头。请框选七列课程区域，并打开“没有星期表头”。"
        case .noCourses: return "未识别到包含周次和节次的课程。请用更清晰的理论课表截图，或手动添加课程。"
        }
    }
}
