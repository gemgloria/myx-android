import Foundation

enum AcademicCalendar {
    static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.firstWeekday = 2
        calendar.timeZone = .current
        return calendar
    }
    static let weekdayNames = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]
    static func date(from text: String) -> Date? {
        let parts = text.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3, let date = calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2])),
              string(from: date) == text else { return nil }
        return date
    }
    static func string(from date: Date) -> String {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year!, parts.month!, parts.day!)
    }
    static func weekday(_ date: Date) -> Int { (calendar.component(.weekday, from: date) + 5) % 7 + 1 }
    static func monday(of date: Date) -> Date {
        calendar.date(byAdding: .day, value: 1 - weekday(date), to: calendar.startOfDay(for: date))!
    }
    static func addDays(_ days: Int, to date: Date) -> Date {
        calendar.date(byAdding: .day, value: days, to: date)!
    }
    static func date(week: Int, weekday: Int = 1, semester: Semester) -> Date {
        addDays((week - 1) * 7 + weekday - 1, to: date(from: semester.firstMonday)!)
    }
    static func week(on date: Date, semester: Semester) -> Int? {
        guard let start = self.date(from: semester.firstMonday) else { return nil }
        let dayCount = calendar.dateComponents([.day], from: start, to: calendar.startOfDay(for: date)).day!
        guard dayCount >= 0, dayCount < semester.weekCount * 7 else { return nil }
        return dayCount / 7 + 1
    }
    static func status(on date: Date, semester: Semester) -> String {
        if let week = week(on: date, semester: semester) { return "第 \(week) 周" }
        if date < self.date(from: semester.firstMonday)! { return "学期尚未开始" }
        return "本学期已结束"
    }
    static func nearestWeek(on date: Date, semester: Semester) -> Int {
        week(on: date, semester: semester) ?? (date < self.date(from: semester.firstMonday)! ? 1 : semester.weekCount)
    }
    static func atMinute(_ minute: Int, on date: Date) -> Date {
        calendar.date(bySettingHour: minute / 60, minute: minute % 60, second: 0, of: date)!
    }
}

struct Lesson: Identifiable, Sendable {
    var course: Course
    var start: Date
    var end: Date
    var id: String { course.id.uuidString + AcademicCalendar.string(from: start) }
}

enum ScheduleEngine {
    static func lessons(on date: Date, semester: Semester) -> [Lesson] {
        guard let week = AcademicCalendar.week(on: date, semester: semester) else { return [] }
        let weekday = AcademicCalendar.weekday(date)
        return semester.courses.compactMap { course in
            guard course.weekday == weekday, course.isActive(week: week),
                  let first = semester.periods.first(where: { $0.id == course.startPeriod }),
                  let last = semester.periods.first(where: { $0.id == course.endPeriod }) else { return nil }
            return Lesson(course: course, start: AcademicCalendar.atMinute(first.startMinute, on: date),
                          end: AcademicCalendar.atMinute(last.endMinute, on: date))
        }.sorted { $0.start == $1.start ? $0.course.name < $1.course.name : $0.start < $1.start }
    }
    static func nextLesson(after date: Date, semester: Semester) -> Lesson? {
        let start = max(AcademicCalendar.calendar.startOfDay(for: date), AcademicCalendar.date(from: semester.firstMonday)!)
        let end = AcademicCalendar.date(week: semester.weekCount + 1, semester: semester)
        guard start < end else { return nil }
        var day = start
        while day < end {
            if let next = lessons(on: day, semester: semester).first(where: { $0.end > date }) { return next }
            day = AcademicCalendar.addDays(1, to: day)
        }
        return nil
    }
    static func conflicts(for course: Course, in semester: Semester) -> [Course] {
        semester.courses.filter {
            $0.id != course.id && $0.weekday == course.weekday &&
            $0.startPeriod <= course.endPeriod && $0.endPeriod >= course.startPeriod &&
            !Set($0.weeks).isDisjoint(with: course.weeks)
        }
    }
}

struct CoursePlacement: Identifiable {
    var course: Course
    var startPeriod: Int
    var endPeriod: Int
    var activeCount: Int
    var alternativesCount: Int
    var id: UUID { course.id }
}

enum CourseLayout {
    // A time slot displays one card for this week. Opening it reveals every arrangement.
    static func placements(courses: [Course], weekday: Int, week: Int) -> [CoursePlacement] {
        let candidates = courses.filter { $0.weekday == weekday && $0.isActive(week: week) }.sorted {
            if $0.startPeriod != $1.startPeriod { return $0.startPeriod < $1.startPeriod }
            if $0.endPeriod != $1.endPeriod { return $0.endPeriod > $1.endPeriod }
            if $0.name != $1.name { return $0.name < $1.name }
            return $0.id.uuidString < $1.id.uuidString
        }
        var groups: [[Course]] = []
        var current: [Course] = []
        var maxEnd = 0
        for course in candidates {
            if !current.isEmpty && course.startPeriod > maxEnd {
                groups.append(current); current = []; maxEnd = 0
            }
            current.append(course); maxEnd = max(maxEnd, course.endPeriod)
        }
        if !current.isEmpty { groups.append(current) }
        return groups.map { group in
            let representative = group[0]
            let start = group.map(\.startPeriod).min()!
            let end = group.map(\.endPeriod).max()!
            let alternatives = courses.filter { $0.weekday == weekday && $0.startPeriod <= end && $0.endPeriod >= start }
            return .init(course: representative, startPeriod: start, endPeriod: end,
                         activeCount: group.count, alternativesCount: alternatives.count)
        }
    }
    static func alternatives(for course: Course, courses: [Course], week: Int) -> [Course] {
        let placement = placements(courses: courses, weekday: course.weekday, week: week)
            .first { $0.startPeriod <= course.endPeriod && $0.endPeriod >= course.startPeriod }
        let start = placement?.startPeriod ?? course.startPeriod
        let end = placement?.endPeriod ?? course.endPeriod
        return courses.filter { $0.weekday == course.weekday && $0.startPeriod <= end && $0.endPeriod >= start }.sorted {
            if $0.isActive(week: week) != $1.isActive(week: week) { return $0.isActive(week: week) }
            if ($0.id == course.id) != ($1.id == course.id) { return $0.id == course.id }
            if ($0.weeks.min() ?? 0) != ($1.weeks.min() ?? 0) { return ($0.weeks.min() ?? 0) < ($1.weeks.min() ?? 0) }
            return $0.name < $1.name
        }
    }
}
