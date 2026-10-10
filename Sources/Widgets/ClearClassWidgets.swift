import SwiftUI
import WidgetKit

struct CourseTimelineProvider: TimelineProvider {
    func placeholder(in context: Context) -> CourseEntry {
        var semester = Semester.empty()
        semester.courses = [Course(name: "新能源材料基础", teacher: "", location: "教三北413",
                                   weekday: AcademicCalendar.weekday(.now), startPeriod: 3, endPeriod: 4,
                                   weeks: [1], color: .indigo)]
        return .init(date: .now, state: .init(selectedSemesterID: semester.id, semesters: [semester]))
    }
    func getSnapshot(in context: Context, completion: @escaping (CourseEntry) -> Void) {
        completion(context.isPreview ? placeholder(in: context) : loadEntry(at: .now))
    }
    func getTimeline(in context: Context, completion: @escaping (Timeline<CourseEntry>) -> Void) {
        let now = Date.now
        let loaded = loadEntry(at: now)
        let state = loaded.state
        let horizon = AcademicCalendar.addDays(3, to: AcademicCalendar.calendar.startOfDay(for: now))
        var dates: Set<Date> = [now]
        for dayOffset in 0...2 {
            let day = AcademicCalendar.addDays(dayOffset, to: AcademicCalendar.calendar.startOfDay(for: now))
            if day > now { dates.insert(day) }
            if let semester = state?.selectedSemester {
                for lesson in ScheduleEngine.lessons(on: day, semester: semester) {
                    if lesson.start > now { dates.insert(lesson.start) }
                    if lesson.end > now { dates.insert(lesson.end) }
                }
            }
        }
        let entries = dates.sorted().map { CourseEntry(date: $0, state: state, status: loaded.status) }
        completion(Timeline(entries: entries, policy: .after(horizon)))
    }
    private func loadEntry(at date: Date) -> CourseEntry {
        guard SharedRepository.sharedContainerAvailable else {
            return .init(date: date, state: nil, status: .sharingUnavailable)
        }
        do { return .init(date: date, state: try SharedRepository.load(requireShared: true)) }
        catch { return .init(date: date, state: nil, status: .unreadable) }
    }
}


struct ClearClassWidget: Widget {
    let kind = "ClearClassSchedule"
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: CourseTimelineProvider()) { CourseWidgetView(entry: $0) }
            .configurationDisplayName("烨昕的课表")
            .description("下一门课、今日课表和锁屏课程。")
            .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .accessoryInline, .accessoryCircular, .accessoryRectangular])
            .contentMarginsDisabled()
    }
}

@main
struct ClearClassWidgets: WidgetBundle {
    var body: some Widget { ClearClassWidget() }
}
