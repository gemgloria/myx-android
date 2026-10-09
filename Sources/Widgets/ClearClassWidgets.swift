import SwiftUI
import WidgetKit

struct CourseEntry: TimelineEntry {
    let date: Date
    let state: ScheduleState?
    var semester: Semester? { state?.selectedSemester }
    var today: [Lesson] { semester.map { ScheduleEngine.lessons(on: date, semester: $0) } ?? [] }
    var next: Lesson? { semester.flatMap { ScheduleEngine.nextLesson(after: date, semester: $0) } }
}

struct CourseTimelineProvider: TimelineProvider {
    func placeholder(in context: Context) -> CourseEntry {
        var semester = Semester.empty()
        semester.courses = [Course(name: "新能源材料基础", teacher: "", location: "教三北413",
                                   weekday: AcademicCalendar.weekday(.now), startPeriod: 3, endPeriod: 4,
                                   weeks: [1], color: .indigo)]
        return .init(date: .now, state: .init(selectedSemesterID: semester.id, semesters: [semester]))
    }
    func getSnapshot(in context: Context, completion: @escaping (CourseEntry) -> Void) {
        completion(context.isPreview ? placeholder(in: context) : CourseEntry(date: .now, state: try? SharedRepository.load(requireShared: true)))
    }
    func getTimeline(in context: Context, completion: @escaping (Timeline<CourseEntry>) -> Void) {
        let now = Date.now
        let state = try? SharedRepository.load(requireShared: true)
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
        let entries = dates.sorted().map { CourseEntry(date: $0, state: state) }
        completion(Timeline(entries: entries, policy: .after(horizon)))
    }
}

struct CourseWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: CourseEntry
    private var targetURL: URL {
        if let next = entry.next { return courseURL(next) }
        return URL(string: "clearclass://today")!
    }
    var body: some View {
        Group {
            switch family {
            case .accessoryInline: inlineView
            case .accessoryCircular: circularView
            case .accessoryRectangular: rectangularView
            case .systemMedium, .systemLarge: todayView.liquidGlass(tint: .indigo, radius: 26)
            default: nextView.liquidGlass(tint: .indigo, radius: 26)
            }
        }.widgetURL(targetURL).containerBackground(for: .widget) {
            if family == .systemSmall || family == .systemMedium || family == .systemLarge {
                LinearGradient(colors: [Color(uiColor: .systemBackground), Color.indigo.opacity(0.09), Color.cyan.opacity(0.06)], startPoint: .topLeading, endPoint: .bottomTrailing)
            }
        }
    }

    private var nextView: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("烨昕的课表").font(KaiFont.caption.weight(.semibold))
                Spacer()
                if let semester = entry.semester {
                    Text(AcademicCalendar.week(on: entry.date, semester: semester).map { "第\($0)周" } ?? "休息日")
                        .font(KaiFont.caption2).foregroundStyle(.secondary)
                }
            }
            if let next = entry.next {
                Text(next.start <= entry.date ? "正在上课" : nextDayLabel(next)).font(KaiFont.caption2).foregroundStyle(.secondary)
                Text(next.course.name).font(KaiFont.headline).lineLimit(3).privacySensitive()
                Spacer(minLength: 0)
                HStack(spacing: 5) {
                    Image(systemName: "clock").font(KaiFont.caption2)
                    Text(next.start, style: .time).font(KaiFont.subheadline.weight(.semibold))
                }
                Text(next.course.location.isEmpty ? "教室待补充" : next.course.location)
                    .font(KaiFont.caption).foregroundStyle(.secondary).lineLimit(1).privacySensitive()
            } else {
                Spacer(minLength: 0)
                Image(systemName: "leaf").font(KaiFont.title2).foregroundStyle(.indigo)
                Text(entry.semester == nil ? "打开烨昕的课表，导入课表" : "暂时没有课程").font(KaiFont.subheadline)
                Spacer(minLength: 0)
            }
        }.padding(16)
    }
    private var todayView: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("今日课表").font(KaiFont.headline)
                Spacer()
                Text(entry.date.formatted(.dateTime.month().day().weekday(.abbreviated)))
                    .font(KaiFont.caption).foregroundStyle(.secondary)
            }
            let visible = family == .systemLarge ? entry.today : entry.today.filter { $0.end > entry.date }
            if visible.isEmpty {
                VStack(spacing: 10) {
                    Image(systemName: "leaf").font(KaiFont.title2).foregroundStyle(.indigo)
                    Text(entry.semester == nil ? "打开烨昕的课表，导入课表" : entry.today.isEmpty ? "今天没有课" : "今天的课已结束")
                        .font(KaiFont.subheadline).foregroundStyle(.secondary)
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ForEach(Array(visible.prefix(family == .systemLarge ? 7 : 3))) { lesson in
                    Link(destination: courseURL(lesson)) {
                        HStack(spacing: 10) {
                            RoundedRectangle(cornerRadius: 2).fill(lesson.course.color.tint).frame(width: 3)
                            Text(lesson.start, style: .time).font(KaiFont.caption.weight(.medium)).monospacedDigit().frame(width: 45, alignment: .leading)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(lesson.course.name).font(KaiFont.subheadline.weight(.medium)).lineLimit(1).privacySensitive()
                                if family == .systemLarge {
                                    Text(lesson.course.location).font(KaiFont.caption2).foregroundStyle(.secondary).lineLimit(1).privacySensitive()
                                }
                            }
                            Spacer(minLength: 0)
                            if family == .systemMedium { Text(lesson.course.location).font(KaiFont.caption2).foregroundStyle(.secondary).lineLimit(1).privacySensitive() }
                        }.frame(minHeight: family == .systemLarge ? 32 : 22)
                            .opacity(lesson.end <= entry.date ? 0.55 : 1)
                    }.buttonStyle(.plain)
                }
                if visible.count > (family == .systemLarge ? 7 : 3) {
                    Text("还有 \(visible.count - (family == .systemLarge ? 7 : 3)) 门课").font(KaiFont.caption2).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }
        }.padding(16)
    }
    private var inlineView: some View {
        Group {
            if let next = entry.next { Text("\(next.start.formatted(.dateTime.hour().minute())) \(next.course.name)").privacySensitive() }
            else { Text("烨昕的课表 · 暂时没有课程") }
        }
    }
    private var rectangularView: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let next = entry.next {
                HStack { Image(systemName: "calendar"); Text(nextDayLabel(next)); Text(next.start, style: .time) }.font(KaiFont.caption)
                Text(next.course.name).font(KaiFont.headline).lineLimit(1).privacySensitive()
                Text(next.course.location).font(KaiFont.caption).lineLimit(1).privacySensitive()
            } else { Text("烨昕的课表").font(KaiFont.headline); Text("暂时没有课程").font(KaiFont.caption) }
        }
    }
    private var circularView: some View {
        VStack(spacing: 1) {
            Text("今日剩余").font(KaiFont.system(size: 8))
            Text("\(entry.today.filter { $0.end > entry.date }.count)").font(KaiFont.title2.weight(.semibold))
            Text("门课").font(KaiFont.system(size: 9))
        }.widgetAccentable()
    }
    private func nextDayLabel(_ lesson: Lesson) -> String {
        if lesson.start <= entry.date { return "上课中" }
        if AcademicCalendar.calendar.isDate(lesson.start, inSameDayAs: entry.date) { return "下一门课" }
        if AcademicCalendar.calendar.isDate(lesson.start, inSameDayAs: AcademicCalendar.addDays(1, to: entry.date)) { return "明天" }
        return lesson.start.formatted(.dateTime.month().day())
    }
    private func courseURL(_ lesson: Lesson) -> URL {
        let week = entry.semester.map { AcademicCalendar.nearestWeek(on: lesson.start, semester: $0) } ?? 1
        return URL(string: "clearclass://today?course=" + lesson.course.id.uuidString + "&week=" + String(week))!
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
