import XCTest
import UIKit
import SwiftUI
import WidgetKit
@testable import ClearClass

final class ScheduleTests: XCTestCase {
    func testNextLessonAgreesWithDayScanAcrossSemester() {
        let semester = ExampleSchedule.semester()
        for dayOffset in stride(from: -3, through: 143, by: 3) {
            for minute in [0, 575, 690, 1200] {
                let day = AcademicCalendar.addDays(dayOffset, to: AcademicCalendar.date(from: semester.firstMonday)!)
                let date = AcademicCalendar.atMinute(minute, on: day)
                var expected: Lesson?
                var scan = max(day, AcademicCalendar.date(from: semester.firstMonday)!)
                let end = AcademicCalendar.date(week: semester.weekCount + 1, semester: semester)
                while scan < end {
                    if let lesson = ScheduleEngine.lessons(on: scan, semester: semester).first(where: { $0.end > date }) {
                        expected = lesson; break
                    }
                    scan = AcademicCalendar.addDays(1, to: scan)
                }
                let next = ScheduleEngine.nextLesson(after: date, semester: semester)
                XCTAssertEqual(next?.id, expected?.id)
                XCTAssertEqual(next?.start, expected?.start)
            }
        }
    }

    func testWidgetEntryKeepsEmptyAndPermissionStatesReadable() {
        XCTAssertEqual(CourseEntry(date: .now, state: nil).emptyMessage, "打开 App，导入课表")
        XCTAssertEqual(CourseEntry(date: .now, state: nil, status: .sharingUnavailable).emptyMessage, "打开 App，检查小组件权限")
        XCTAssertEqual(CourseEntry(date: .now, state: nil, status: .unreadable).emptyMessage, "打开 App，更新课表")
    }

    @MainActor
    func testWidgetSnapshotContainsTextForDataAndFallbackStates() throws {
        let semester = ExampleSchedule.semester()
        let date = AcademicCalendar.atMinute(540, on: AcademicCalendar.date(from: "2026-10-10")!)
        let ready = CourseEntry(date: date, state: .init(selectedSemesterID: semester.id, semesters: [semester]))
        let entries: [(String, CourseEntry)] = [("course", ready), ("empty", .init(date: date, state: nil)),
            ("permissions", .init(date: date, state: nil, status: .sharingUnavailable))]
        let directory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("WidgetSnapshots")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        for (name, entry) in entries {
            for (modeName, mode) in [("color", WidgetRenderingMode.fullColor), ("clear", .accented)] {
                let renderer = ImageRenderer(content: CourseWidgetContent(entry: entry, family: .systemSmall, renderingMode: mode)
                    .environment(\.colorScheme, .light).frame(width: 170, height: 180).background(.white))
                renderer.scale = 2
                let image = try XCTUnwrap(renderer.uiImage)
                let png = try XCTUnwrap(image.pngData())
                try png.write(to: directory.appendingPathComponent("\(name)-\(modeName).png"))
                let attachment = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
                attachment.name = "widget-\(name)-\(modeName)"
                attachment.lifetime = .keepAlways
                add(attachment)
                // Check the header band: it contains the app title, not the leaf
                // icon. This catches a blank snapshot even when data is missing.
                let cgImage = try XCTUnwrap(image.cgImage)
                var pixels = [UInt8](repeating: 255, count: cgImage.width * cgImage.height * 4)
                let darkPixels = pixels.withUnsafeMutableBytes { buffer -> Int in
                    let context = CGContext(data: buffer.baseAddress, width: cgImage.width, height: cgImage.height,
                        bitsPerComponent: 8, bytesPerRow: cgImage.width * 4, space: CGColorSpaceCreateDeviceRGB(),
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
                    context.draw(cgImage, in: CGRect(x: 0, y: 0, width: cgImage.width, height: cgImage.height))
                    let data = buffer.bindMemory(to: UInt8.self)
                    return (24..<84).reduce(0) { count, y in
                        count + (24..<(cgImage.width - 24)).filter { x in
                            let offset = (y * cgImage.width + x) * 4
                            return Int(data[offset]) + Int(data[offset + 1]) + Int(data[offset + 2]) < 630
                        }.count
                    }
                }
                XCTAssertGreaterThan(darkPixels, 100, "\(name)-\(modeName) must render its title.")
            }
        }
    }
    @MainActor
    func testKaiFontIsBundledAndRegistered() {
        XCTAssertNotNil(Bundle.main.url(forResource: "LXGWWenKai-Regular", withExtension: "ttf"))
        XCTAssertNotNil(UIFont(name: KaiFont.name, size: 17))
        XCTAssertEqual(Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String, "烨昕的课表")
    }
    func testSpreadsheetImportRunsInNativeJavaScriptCore() async throws {
        let csv = "\u{FEFF}课程名称,星期,节次,周次,教师,教室\n太阳能利用概论,周二,3-4,\"1-4,6-8\",李海金,教三北413\n新能源专业英语,周二,3-4,11-18,毛可可,教三北413"
        let result = try await SpreadsheetService.recognize(data: Data(csv.utf8))
        XCTAssertEqual(result.candidates.count, 2)
        XCTAssertEqual(result.candidates[0].course.name, "太阳能利用概论")
        XCTAssertEqual(result.candidates[0].course.weeks, [1, 2, 3, 4, 6, 7, 8])
        XCTAssertEqual(result.candidates[1].course.weekday, 2)
        XCTAssertEqual(result.candidates[1].course.startPeriod, 3)
        XCTAssertEqual(result.candidates[1].course.endPeriod, 4)
        XCTAssertEqual(result.candidates[1].course.location, "教三北413")
    }
    func testWeekExpressionsPreserveGapsAndParity() throws {
        XCTAssertEqual(try WeekExpression.parse("1-4,6-8"), [1, 2, 3, 4, 6, 7, 8])
        XCTAssertEqual(try WeekExpression.parse("１－８（单周）"), [1, 3, 5, 7])
        XCTAssertEqual(try WeekExpression.parse("3,7,9,11,14,16"), [3, 7, 9, 11, 14, 16])
        XCTAssertThrowsError(try WeekExpression.parse("1-8单双周"))
        XCTAssertThrowsError(try WeekExpression.parse("8-1"))
        XCTAssertThrowsError(try WeekExpression.parse("1-21", maxWeek: 20))
        XCTAssertThrowsError(try WeekExpression.parse("1,,3"))
        XCTAssertEqual(WeekExpression.format([8, 7, 6, 4, 3, 2, 1, 1]), "1-4,6-8")
    }
    func testAcademicWeekBoundaries() throws {
        let semester = Semester(name: "Test", firstMonday: "2026-08-31", weekCount: 18)
        XCTAssertNil(AcademicCalendar.week(on: AcademicCalendar.date(from: "2026-08-30")!, semester: semester))
        XCTAssertEqual(AcademicCalendar.week(on: AcademicCalendar.date(from: "2026-09-06")!, semester: semester), 1)
        XCTAssertEqual(AcademicCalendar.week(on: AcademicCalendar.date(from: "2026-10-10")!, semester: semester), 6)
        XCTAssertEqual(AcademicCalendar.weekday(AcademicCalendar.date(from: "2026-10-11")!), 7)
        XCTAssertNil(AcademicCalendar.week(on: AcademicCalendar.date(week: 19, semester: semester), semester: semester))
        XCTAssertNil(AcademicCalendar.date(from: "2026-02-31"))
    }
    func testSemesterTransitionChangesNextCourse() {
        let semester = ExampleSchedule.semester()
        let before = AcademicCalendar.atMinute(0, on: AcademicCalendar.date(from: "2026-08-30")!)
        XCTAssertEqual(ScheduleEngine.nextLesson(after: before, semester: semester)?.course.name, "概率论与数理统计 C")
        let after = AcademicCalendar.date(week: 21, semester: semester)
        XCTAssertNil(ScheduleEngine.nextLesson(after: after, semester: semester))
    }
    func testSundayFourPeriodLessonAndFutureWeeks() {
        let semester = ExampleSchedule.semester()
        let sunday = AcademicCalendar.date(from: "2026-10-11")!
        let lesson = ScheduleEngine.lessons(on: sunday, semester: semester).first!
        XCTAssertEqual(lesson.course.name, "电工学 2")
        XCTAssertEqual(lesson.course.startPeriod, 1)
        XCTAssertEqual(lesson.course.endPeriod, 4)
        XCTAssertEqual(lesson.end, AcademicCalendar.atMinute(690, on: sunday))
        let later = AcademicCalendar.date(week: 12, weekday: 7, semester: semester)
        XCTAssertTrue(ScheduleEngine.lessons(on: later, semester: semester).isEmpty)
    }
    func testEndTimeDoesNotKeepFinishedLessonAsNext() {
        var semester = Semester(name: "Test", firstMonday: "2026-08-31")
        semester.courses = [Course(name: "One", weekday: 1, startPeriod: 1, endPeriod: 2, weeks: [1]),
                            Course(name: "Two", weekday: 1, startPeriod: 3, endPeriod: 4, weeks: [1])]
        let day = AcademicCalendar.date(from: semester.firstMonday)!
        XCTAssertEqual(ScheduleEngine.nextLesson(after: AcademicCalendar.atMinute(574, on: day), semester: semester)?.course.name, "One")
        XCTAssertEqual(ScheduleEngine.nextLesson(after: AcademicCalendar.atMinute(575, on: day), semester: semester)?.course.name, "Two")
        XCTAssertNil(ScheduleEngine.nextLesson(after: AcademicCalendar.atMinute(690, on: day), semester: semester))
    }
    func testConflictsAndAlternateWeeks() {
        let semester = ExampleSchedule.semester()
        let extra = semester.courses.first { $0.location == "教一107" }!
        XCTAssertEqual(ScheduleEngine.conflicts(for: extra, in: semester).count, 1)
        let placement = CourseLayout.placements(courses: semester.courses, weekday: 3, week: 6)
        XCTAssertEqual(placement.filter { $0.startPeriod == 3 }.map(\.activeCount), [2])
        let thursday = CourseLayout.placements(courses: semester.courses, weekday: 4, week: 6)
        XCTAssertFalse(thursday.contains { $0.course.name == "新能源专业英语" })
        let week11 = CourseLayout.placements(courses: semester.courses, weekday: 4, week: 11)
        XCTAssertTrue(week11.contains { $0.course.name == "新能源专业英语" })
        XCTAssertFalse(week11.contains { $0.course.name == "太阳能利用概论" })
        let solar = thursday.first { $0.course.name == "太阳能利用概论" }!.course
        let expanded = CourseLayout.alternatives(for: solar, courses: semester.courses, week: 6)
        XCTAssertEqual(expanded.map(\.name), ["太阳能利用概论", "新能源专业英语"])
        XCTAssertEqual(expanded.map { $0.isActive(week: 6) }, [true, false])
        let english = week11.first { $0.course.name == "新能源专业英语" }!.course
        XCTAssertEqual(CourseLayout.alternatives(for: english, courses: semester.courses, week: 11).map(\.name),
                       ["新能源专业英语", "太阳能利用概论"])
    }
    func testInactiveOnlyTimeSlotRemainsEmpty() {
        let course = Course(name: "隔周课", weekday: 2, startPeriod: 5, endPeriod: 6, weeks: [1, 3, 5])
        XCTAssertTrue(CourseLayout.placements(courses: [course], weekday: 2, week: 2).isEmpty)
        XCTAssertEqual(CourseLayout.placements(courses: [course], weekday: 2, week: 3).count, 1)
    }
    func testScreenshotParserHandlesSplitWeekLineAndTwoCourses() throws {
        let strings = ["太阳能利用概论", "李海金,王健敏,汪波", "1-10(周)[01-02节]", "教三北413", "---------",
                       "新能源专业英语", "毛可可", "11-18(周)[01-", "02节]", "教三北413"]
        let lines = strings.enumerated().map { OCRLine(text: $0.element, y: Double($0.offset) / 20) }
        let parsed = TimetableParser.parseColumn(lines, weekday: 4)
        XCTAssertEqual(parsed.candidates.count, 2)
        XCTAssertEqual(parsed.candidates[1].course.name, "新能源专业英语")
        XCTAssertEqual(parsed.candidates[1].course.startPeriod, 1)
        XCTAssertEqual(parsed.candidates[1].course.endPeriod, 2)
        XCTAssertEqual(parsed.candidates[0].course.teacher, "李海金,王健敏,汪波")
    }
    func testScreenshotParserHandlesWrappedTitleAndTeacherList() {
        let strings = ["综合能源系统与技", "术", "胡静,李凡,刘慧,陈", "志杰", "1-8(周)[03-04节]", "教三北413"]
        let parsed = TimetableParser.parseColumn(strings.enumerated().map { OCRLine(text: $0.element, y: Double($0.offset)) }, weekday: 2)
        XCTAssertEqual(parsed.candidates[0].course.name, "综合能源系统与技术")
        XCTAssertEqual(parsed.candidates[0].course.teacher, "胡静,李凡,刘慧,陈志杰")
    }
    func testGridRequiresFullWeekHeader() {
        var lines = (1...7).map { OCRLine(text: "星期" + ["一", "二", "三", "四", "五", "六", "日"][$0 - 1],
                                        x: 0.13 + Double($0 - 1) * 0.1, y: 0.1, width: 0.04, height: 0.01) }
        XCTAssertNotNil(TimetableParser.detectGrid(in: lines))
        lines.removeLast()
        XCTAssertNil(TimetableParser.detectGrid(in: lines))
    }
    func testBackupValidationAndRoundTrip() throws {
        let semester = ExampleSchedule.semester()
        let state = ScheduleState(selectedSemesterID: semester.id, semesters: [semester])
        XCTAssertEqual(try SharedRepository.decode(SharedRepository.encode(state)), state)
        var invalid = state
        invalid.semesters[0].firstMonday = "2026-09-01"
        XCTAssertThrowsError(try invalid.validate())
        invalid = state; invalid.semesters[0].periods[1].startMinute = 500
        XCTAssertThrowsError(try invalid.validate())
    }
}
