import SwiftUI

struct TodayView: View {
    @EnvironmentObject private var store: ScheduleStore
    @State private var now = AppPreview.date
    let openCourse: (Course) -> Void
    var body: some View {
        let lessons = ScheduleEngine.lessons(on: now, semester: store.semester)
        ZStack {
            AppBackground()
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    VStack(alignment: .leading, spacing: 7) {
                        Text("今天").font(KaiFont.system(size: 32, weight: .semibold, design: .rounded))
                        Text(now.formatted(.dateTime.month().day().weekday(.wide)) + " · " + AcademicCalendar.status(on: now, semester: store.semester))
                            .font(KaiFont.subheadline).foregroundStyle(.secondary)
                    }
                    if let next = ScheduleEngine.nextLesson(after: now, semester: store.semester) {
                        Button { openCourse(next.course) } label: {
                            VStack(alignment: .leading, spacing: 13) {
                                HStack {
                                    Label(next.start <= now ? "正在上课" : "下一门课", systemImage: next.start <= now ? "sparkle" : "clock")
                                        .font(KaiFont.caption.weight(.medium)).foregroundStyle(.secondary)
                                    Spacer()
                                    Image(systemName: "arrow.up.right").font(KaiFont.caption)
                                }
                                Text(next.course.name).font(KaiFont.title2.weight(.semibold))
                                HStack(spacing: 8) {
                                    Text(next.start.formatted(.dateTime.month().day()))
                                    Text(next.start, style: .time)
                                    Text("–")
                                    Text(next.end, style: .time)
                                }.font(KaiFont.subheadline).foregroundStyle(.secondary)
                                Label(next.course.location.isEmpty ? "教室待补充" : next.course.location, systemImage: "mappin.and.ellipse")
                                    .font(KaiFont.subheadline).foregroundStyle(next.course.color.tint)
                            }.padding(23).frame(maxWidth: .infinity, alignment: .leading)
                                .liquidGlass(tint: next.course.color.tint, radius: 28, interactive: true)
                        }.buttonStyle(.plain)
                    }
                    Text("今日安排").font(KaiFont.headline)
                    if lessons.isEmpty {
                        EmptyScheduleView(title: "今天没有课", subtitle: "留一点时间给自己。", symbol: "leaf")
                    } else {
                        ForEach(lessons) { lesson in
                            Button { openCourse(lesson.course) } label: {
                                HStack(alignment: .top, spacing: 15) {
                                    VStack(spacing: 5) {
                                        Text(lesson.start, style: .time).font(KaiFont.subheadline.weight(.semibold))
                                        Text(lesson.end, style: .time).font(KaiFont.caption).foregroundStyle(.secondary)
                                    }.monospacedDigit().frame(minWidth: 46)
                                    RoundedRectangle(cornerRadius: 2).fill(lesson.course.color.tint).frame(width: 3, height: 42)
                                    VStack(alignment: .leading, spacing: 7) {
                                        Text(lesson.course.name).font(KaiFont.headline)
                                        Text(lesson.course.location.isEmpty ? "教室待补充" : lesson.course.location).font(KaiFont.subheadline).foregroundStyle(.secondary)
                                        if lesson.end <= now { Text("已结束").font(KaiFont.caption).foregroundStyle(.secondary) }
                                        else if lesson.start <= now { Text("上课中").font(KaiFont.caption).foregroundStyle(.indigo) }
                                        if !ScheduleEngine.conflicts(for: lesson.course, in: store.semester).isEmpty {
                                            Label("存在时间重叠，请核对", systemImage: "exclamationmark.circle").font(KaiFont.caption).foregroundStyle(.orange)
                                        }
                                    }
                                    Spacer(minLength: 0)
                                }.padding(18).frame(maxWidth: .infinity, alignment: .leading).liquidGlass(radius: 23, interactive: true)
                                    .opacity(lesson.end <= now ? 0.65 : 1)
                            }.buttonStyle(.plain)
                        }
                    }
                }.padding(22).padding(.bottom, 12)
            }
        }.toolbar(.hidden, for: .navigationBar)
            .onReceive(Timer.publish(every: 30, on: .main, in: .common).autoconnect()) { now = $0 }
    }
}
