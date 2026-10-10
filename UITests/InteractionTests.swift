import XCTest
import UIKit

final class InteractionTests: XCTestCase {
    private var app: XCUIApplication!
    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["--preview-demo"]
        app.launch()
        let visible = app.buttons["tab-0"].waitForExistence(timeout: 8)
        if !visible { print(app.debugDescription) }
        XCTAssertTrue(visible)
    }

    func testBottomButtonsRespondAtEdgesAndCenters() {
        let points = [CGVector(dx: 0.12, dy: 0.22), CGVector(dx: 0.5, dy: 0.5), CGVector(dx: 0.88, dy: 0.78)]
        for point in points {
            for tab in [1, 2, 0] {
                let button = app.buttons["tab-\(tab)"]
                button.coordinate(withNormalizedOffset: point).tap()
                XCTAssertTrue(button.isSelected, "A tap in the entire bottom button must select its page.")
                XCTAssertFalse(app.buttons["close-course"].exists, "Bottom navigation must not open an underlying course.")
            }
        }
    }

    func testCourseOpensFromEmptyMiddleAndLowerCorners() {
        let points = [CGVector(dx: 0.5, dy: 0.5), CGVector(dx: 0.85, dy: 0.82), CGVector(dx: 0.15, dy: 0.18)]
        for (index, point) in points.enumerated() {
            let course = app.buttons["course-2-3"]
            XCTAssertTrue(course.waitForExistence(timeout: 3))
            course.coordinate(withNormalizedOffset: point).tap()
            let close = app.buttons["close-course"]
            XCTAssertTrue(close.waitForExistence(timeout: 2))
            XCTAssertTrue(app.staticTexts["新能源专业英语"].exists)
            assertVisibleText(app.staticTexts["太阳能利用概论"].firstMatch)
            if index == 0 { captureScreen("detail") }
            close.tap()
            XCTAssertTrue(course.waitForExistence(timeout: 2))
        }
    }

    func testBottomBarDoesNotPassTapsIntoScrolledTimetable() {
        app.swipeUp()
        app.swipeUp()
        for tab in [1, 0, 2, 0] {
            let button = app.buttons["tab-\(tab)"]
            button.coordinate(withNormalizedOffset: CGVector(dx: 0.82, dy: 0.78)).tap()
            XCTAssertTrue(button.isSelected)
            XCTAssertFalse(app.buttons["close-course"].exists)
        }
    }

    func testRepeatedPageSwitchingHasNoMissedActions() {
        for _ in 0..<4 {
            for tab in [1, 2, 0] {
                let button = app.buttons["tab-\(tab)"]
                button.tap()
                XCTAssertTrue(button.isSelected)
            }
        }
        captureScreen("week")
        app.buttons["tab-1"].tap()
        captureScreen("today")
        app.buttons["tab-2"].tap()
        captureScreen("settings")
    }

    func testDeleteRemovesOnlyTheSelectedArrangement() {
        app.buttons["course-2-3"].tap()
        let deleteCurrent = app.buttons["delete-course-太阳能利用概论"]
        XCTAssertTrue(deleteCurrent.waitForExistence(timeout: 3))
        deleteCurrent.tap()
        let destructive = app.buttons["删除此课程安排"]
        XCTAssertTrue(destructive.waitForExistence(timeout: 3))
        let cancel = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "取消", "Cancel")).firstMatch
        if cancel.exists {
            cancel.tap()
        } else {
            // On iOS 26 this dialog can be a popover without a cancel button.
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.12)).tap()
        }
        XCTAssertTrue(destructive.waitForNonExistence(timeout: 3))
        if !app.buttons["close-course"].exists { app.buttons["course-2-3"].tap() }
        XCTAssertTrue(app.buttons["close-course"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["太阳能利用概论"].exists)
        XCTAssertTrue(app.staticTexts["新能源专业英语"].exists)
        let delete = app.buttons["delete-course-新能源专业英语"]
        XCTAssertTrue(delete.waitForExistence(timeout: 3))
        delete.tap()
        let confirm = app.buttons["删除此课程安排"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 2))
        confirm.tap()
        XCTAssertTrue(app.staticTexts["新能源专业英语"].waitForNonExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["太阳能利用概论"].exists)
        captureScreen("after-delete")
        app.buttons["close-course"].tap()
        XCTAssertTrue(app.buttons["course-2-3"].waitForExistence(timeout: 2))
        app.buttons["course-2-3"].tap()
        XCTAssertFalse(app.staticTexts["新能源专业英语"].exists)
        XCTAssertTrue(app.staticTexts["太阳能利用概论"].exists)
    }

    private func captureScreen(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func assertVisibleText(_ element: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        let image = app.screenshot().image.cgImage!
        let scale = CGFloat(image.width) / app.frame.width
        let rect = element.frame
        let minX = max(0, Int(rect.minX * scale)), maxX = min(image.width, Int(rect.maxX * scale))
        let minY = max(0, Int(rect.minY * scale)), maxY = min(image.height, Int(rect.maxY * scale))
        var pixels = [UInt8](repeating: 255, count: image.width * image.height * 4)
        let count = pixels.withUnsafeMutableBytes { buffer -> Int in
            let context = CGContext(data: buffer.baseAddress, width: image.width, height: image.height,
                bitsPerComponent: 8, bytesPerRow: image.width * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
            context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
            let data = buffer.bindMemory(to: UInt8.self)
            var darkPixelCount = 0
            for y in minY..<maxY {
                for x in minX..<maxX {
                    let offset = (y * image.width + x) * 4
                    let red = Int(data[offset])
                    let green = Int(data[offset + 1])
                    let blue = Int(data[offset + 2])
                    if red + green + blue < 530 { darkPixelCount += 1 }
                }
            }
            return darkPixelCount
        }
        XCTAssertGreaterThan(count, 100, "Course text must be visible in the rendered screen, not only in accessibility.", file: file, line: line)
    }
}
