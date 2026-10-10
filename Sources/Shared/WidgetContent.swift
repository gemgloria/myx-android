import SwiftUI
import WidgetKit

enum WidgetDataStatus: Equatable {
    case ready, missing, sharingUnavailable, unreadable
}

struct CourseEntry: TimelineEntry {
    let date: Date
    let state: ScheduleState?
    let today: [Lesson]
    let next: Lesson?
    let status: WidgetDataStatus
    var semester: Semester? { state?.selectedSemester }
    init(date: Date, state: ScheduleState?, status: WidgetDataStatus? = nil) {
        self.date = date
        self.state = state
        self.status = status ?? (state == nil ? .missing : .ready)
        let semester = state?.selectedSemester
        self.today = semester.map { ScheduleEngine.lessons(on: date, semester: $0) } ?? []
        self.next = semester.flatMap { ScheduleEngine.nextLesson(after: date, semester: $0) }
    }
    var emptyMessage: String? {
        switch status {
        case .ready: return nil
        case .missing: return "打开 App，导入课表"
        case .sharingUnavailable: return "打开 App，检查小组件权限"
        case .unreadable: return "打开 App，更新课表"
        }
    }
}

// This exact content is used by the extension, settings preview and native
// snapshot tests. All text stays outside of the removable widget background.
struct CourseWidgetContent: View {
    @Environment(\.widgetRenderingMode) private var renderingMode
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
            case .systemMedium, .systemLarge: todayView
            default: nextView
            }
        }.foregroundStyle(.primary)
            .widgetURL(targetURL)
    }

    private var nextView: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("烨昕的课表").font(KaiFont.caption.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.8)
                Spacer()
                if let semester = entry.semester {
                    Text(AcademicCalendar.week(on: entry.date, semester: semester).map { "第\($0)周" } ?? "休息日")
                        .font(KaiFont.caption2).foregroundStyle(.secondary)
                }
            }
            if let next = entry.next {
                Text(next.start <= entry.date ? "正在上课" : nextDayLabel(next)).font(KaiFont.caption2).foregroundStyle(.secondary)
                Text(next.course.name).font(KaiFont.headline).lineLimit(3).widgetAccentable().privacySensitive()
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
                Text(entry.emptyMessage ?? "暂时没有课程").font(KaiFont.subheadline)
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
                    Text(entry.emptyMessage ?? (entry.today.isEmpty ? "今天没有课" : "今天的课已结束"))
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
            else { Text("烨昕的课表 · " + (entry.emptyMessage ?? "暂时没有课程")) }
        }
    }
    private var rectangularView: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let next = entry.next {
                HStack { Image(systemName: "calendar"); Text(nextDayLabel(next)); Text(next.start, style: .time) }.font(KaiFont.caption)
                Text(next.course.name).font(KaiFont.headline).lineLimit(1).privacySensitive()
                Text(next.course.location).font(KaiFont.caption).lineLimit(1).privacySensitive()
            } else { Text("烨昕的课表").font(KaiFont.headline); Text(entry.emptyMessage ?? "暂时没有课程").font(KaiFont.caption) }
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

struct CourseWidgetView: View {
    @Environment(\.widgetRenderingMode) private var renderingMode
    let entry: CourseEntry
    var body: some View {
        CourseWidgetContent(entry: entry)
            .containerBackground(for: .widget) { WidgetBackground() }
    }
}

struct WidgetBackground: View {
    @Environment(\.widgetRenderingMode) private var renderingMode
    var body: some View {
        if renderingMode == .fullColor {
            LinearGradient(colors: [Color(uiColor: .systemBackground), .indigo.opacity(0.09), .cyan.opacity(0.06)],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
        } else {
            // In accented / clear mode WidgetKit supplies its own Liquid Glass.
            Color.clear
        }
    }
}
