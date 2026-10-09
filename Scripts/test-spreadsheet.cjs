const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const context = {};
vm.createContext(context);
vm.runInContext(fs.readFileSync(path.join(root, 'Resources/xlsx.full.min.js'), 'utf8'), context);
vm.runInContext(fs.readFileSync(path.join(root, 'Resources/SpreadsheetImport.js'), 'utf8'), context);
const parser = context.ClearClassSpreadsheet;
const plain = x => JSON.parse(JSON.stringify(x));
let checks = 0;
function test(name, fn) { fn(); checks++; console.log('PASS', name); }

test('vendor version', () => assert.equal(context.XLSX.version, '0.20.3'));
test('non-contiguous weeks, fullwidth punctuation and parity', () => {
  assert.deepEqual(plain(parser.parseWeeks('１－４，６－８')), [1,2,3,4,6,7,8]);
  assert.deepEqual(plain(parser.parseWeeks('1-8(单周)')), [1,3,5,7]);
  assert.deepEqual(plain(parser.parseWeeks('1-8双周')), [2,4,6,8]);
  assert.throws(() => parser.parseWeeks('1-4单双周'));
  assert.throws(() => parser.parseWeeks('8-1'));
  assert.throws(() => parser.parseWeeks('1,,3'));
});
test('four-period and disjoint-period blocks', () => {
  assert.deepEqual(plain(parser.parsePeriods('[01-02-03-04]节')), [[1,4]]);
  assert.deepEqual(plain(parser.parsePeriods('1,3-4')), [[1,1],[3,4]]);
  assert.throws(() => parser.parsePeriods('4-1'));
});
test('two courses in one cell preserve different rooms and weeks', () => {
  const cell = '\n太阳能利用概论\n李海金,王健敏\n1-10[周]\n教三北413\n[01-02]节\n\n新能源专业英语\n毛可可\n11-18[周]\n教三北112\n[01-02]节\n';
  const parsed = plain(parser.parseCell(cell, 4));
  assert.equal(parsed.length, 2);
  assert.equal(parsed[0].teacher, '李海金,王健敏');
  assert.equal(parsed[1].name, '新能源专业英语');
  assert.equal(parsed[1].weeks[0], 11);
  assert.equal(parsed[1].location, '教三北112');
});
test('blank rooms are preserved without inventing locations', () => {
  const parsed = plain(parser.parseCell('工程训练D\n(分组03)\n温从众\n2-9[周]\n\n[05-06-07-08]节',1));
  assert.equal(parsed[0].name,'工程训练D(分组03)');
  assert.equal(parsed[0].location,'');
  assert.equal(parsed[0].warnings.length,1);
});
test('row-based CSV layout with separated periods', () => {
  const result = plain(parser.parseSheets([{name:'Courses',rows:[
    ['课程名称','星期','开始节次','结束节次','周次','教师','教室'],
    ['传热学','周五','1','2','1-14','汪冬冬','教三北112']
  ]}]));
  assert.equal(result.courses[0].weekday,5);
  assert.equal(result.courses[0].endPeriod,2);
});
test('unrecognized sheets and malformed schedules are surfaced', () => {
  assert.throws(() => parser.parseSheets([{name:'Unrelated',rows:[['not a timetable']]}]));
  assert.throws(() => parser.parseCell('未知课程\n教师\n99[周]\n[1-2]节', 1));
});
const fixture = JSON.parse(fs.readFileSync(path.join(root,'Tests/Fixtures/academic-table.json'),'utf8'));
let fixtureResult;
test('provided academic table fixture: 26 schedules and 4 duplicate rows', () => {
  fixtureResult = plain(parser.parseSheets(fixture));
  assert.equal(fixtureResult.courses.length,26);
  assert.equal(fixtureResult.duplicateCount,4);
  assert.equal(fixtureResult.suggestedSemesterName,'2025–2026 第1学期');
  const mathematics=fixtureResult.courses.filter(c=>c.name==='高等数学A1');
  assert.equal(mathematics.length,1);
  assert.equal(mathematics[0].weekday,6);
  assert.equal(mathematics[0].endPeriod,4);
  const controls=fixtureResult.courses.filter(c=>c.name==='能源行业工业控制系统安全');
  assert.equal(controls.length,3);
  assert.deepEqual(controls.map(c=>c.location),['教三北119','教三南102','教三北306']);
  assert.ok(fixtureResult.warnings[0].includes('没有固定星期或节次'));
});
if (process.argv[2]) test('real uploaded binary XLS matches independent xlrd extraction', () => {
  const data=fs.readFileSync(process.argv[2]);
  const result=JSON.parse(parser.importWorkbook(data.toString('base64')));
  assert.deepEqual(result,fixtureResult);
});
['xlsx','xls','csv'].forEach(bookType => test('binary '+bookType+' reader round trip', () => {
  const workbook = context.XLSX.utils.book_new();
  context.XLSX.utils.book_append_sheet(workbook, context.XLSX.utils.aoa_to_sheet(fixture[0].rows), 'Sheet1');
  const base64 = context.XLSX.write(workbook, {bookType, type:'base64'});
  assert.deepEqual(JSON.parse(parser.importWorkbook(base64)), fixtureResult);
}));
test('UTF-8 TSV reader round trip', () => {
  const sheet=context.XLSX.utils.aoa_to_sheet(fixture[0].rows);
  const text=context.XLSX.utils.sheet_to_csv(sheet,{FS:'\t'});
  const base64=Buffer.from('\ufeff'+text,'utf8').toString('base64');
  assert.deepEqual(JSON.parse(parser.importWorkbook(base64)),fixtureResult);
});
console.log(checks+' spreadsheet regression checks passed.');
