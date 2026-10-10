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
        for point in points {
            let course = app.buttons["course-2-3"]
            XCTAssertTrue(course.waitForExistence(timeout: 3))
            course.coordinate(withNormalizedOffset: point).tap()
            let close = app.buttons["close-course"]
            XCTAssertTrue(close.waitForExistence(timeout: 2))
            XCTAssertTrue(app.staticTexts["新能源专业英语"].exists)
            assertVisibleText(app.staticTexts["太阳能利用概论"].firstMatch)
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
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "Bottom navigation after repeated taps"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testDeleteRemovesOnlyTheSelectedArrangement() {
        app.buttons["course-2-3"].tap()
        let delete = app.buttons["delete-course-新能源专业英语"]
        XCTAssertTrue(delete.waitForExistence(timeout: 3))
        delete.tap()
        let confirm = app.buttons["删除此课程安排"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 2))
        confirm.tap()
        XCTAssertFalse(app.staticTexts["新能源专业英语"].exists)
        XCTAssertTrue(app.staticTexts["太阳能利用概论"].exists)
        app.buttons["close-course"].tap()
        XCTAssertTrue(app.buttons["course-2-3"].waitForExistence(timeout: 2))
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
            return (minY..<maxY).reduce(0) { result, y in
                result + (minX..<maxX).filter { x in
                    let offset = (y * image.width + x) * 4
                    return Int(data[offset]) + Int(data[offset + 1]) + Int(data[offset + 2]) < 530
                }.count
            }
        }
        XCTAssertGreaterThan(count, 100, "Course text must be visible in the rendered screen, not only in accessibility.", file: file, line: line)
    }
}
