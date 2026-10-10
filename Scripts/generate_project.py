#!/usr/bin/env python3
"""Generate a dependency-free, conventional Xcode project from the source tree."""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parents[1]
objects = {}
def identifier(label): return hashlib.sha1(label.encode()).hexdigest()[:24].upper()
def put(label, text):
    key = identifier(label); objects[key] = text; return key
def quoted(value): return json.dumps(str(value), ensure_ascii=False)
def listing(values): return '(' + ', '.join(values) + (',' if values else '') + ')'
def settings(values): return '{' + ' '.join(k + ' = ' + quoted(v) + ';' for k, v in values.items()) + '}'
def file(path, kind):
    return put('file:' + path, '{isa = PBXFileReference; lastKnownFileType = ' + kind + '; path = ' + quoted(path) + '; sourceTree = "<group>";}')
def build_file(ref, extra=''):
    return put('build:' + ref + extra, '{isa = PBXBuildFile; fileRef = ' + ref + '; ' + extra + '}')

all_paths = sorted(p.relative_to(ROOT).as_posix() for p in (ROOT/'Sources').rglob('*.swift'))
refs = {p: file(p, 'sourcecode.swift') for p in all_paths}
test_ref = file('Tests/ScheduleTests.swift', 'sourcecode.swift')
ui_test_ref = file('UITests/InteractionTests.swift', 'sourcecode.swift')
resource_paths = ['Resources/xlsx.full.min.js', 'Resources/SpreadsheetImport.js', 'Resources/SHEETJS-LICENSE.txt', 'Resources/Assets.xcassets', 'Resources/LXGWWenKai-Regular.ttf', 'Resources/LXGWWenKai-Widget.ttf', 'Resources/WENKAI-OFL.txt']
resource_refs = [file(p, 'folder.assetcatalog' if p.endswith('xcassets') else 'text') for p in resource_paths]
configuration_paths = ['Configuration/Settings.xcconfig', 'Configuration/App-Info.plist', 'Configuration/Widget-Info.plist', 'Configuration/App.entitlements', 'Configuration/Widget.entitlements']
configuration_refs = [file(p, 'text.xcconfig' if p.endswith('xcconfig') else 'text.plist.xml') for p in configuration_paths]
products = {}
for name, path, kind in [('ClearClass','ClearClass.app','wrapper.application'), ('ClearClassWidgets','ClearClassWidgets.appex','wrapper.app-extension'), ('ClearClassTests','ClearClassTests.xctest','wrapper.cfbundle'), ('ClearClassUITests','ClearClassUITests.xctest','wrapper.cfbundle')]:
    products[name] = put('product:' + name, '{isa = PBXFileReference; explicitFileType = ' + kind + '; includeInIndex = 0; path = ' + quoted(path) + '; sourceTree = BUILT_PRODUCTS_DIR;}')
product_group = put('products', '{isa = PBXGroup; children = ' + listing(list(products.values())) + '; name = Products; sourceTree = "<group>";}')
main_group = put('mainGroup', '{isa = PBXGroup; children = ' + listing(list(refs.values()) + [test_ref, ui_test_ref] + resource_refs + configuration_refs + [product_group]) + '; sourceTree = "<group>";}')

project_settings = {'SDKROOT':'iphoneos','IPHONEOS_DEPLOYMENT_TARGET':'17.0','SWIFT_VERSION':'5.0','CLANG_ENABLE_MODULES':'YES',
                    'SWIFT_STRICT_CONCURRENCY':'targeted','ENABLE_USER_SCRIPT_SANDBOXING':'YES','GCC_C_LANGUAGE_STANDARD':'gnu17',
                    'CLANG_WARN_DOCUMENTATION_COMMENTS':'YES','CLANG_WARN_UNREACHABLE_CODE':'YES'}
def config_list(label, values, base=True):
    configs=[]
    for name in ['Debug','Release']:
        current=dict(values)
        current['SWIFT_OPTIMIZATION_LEVEL'] = '-Onone' if name == 'Debug' else '-O'
        current['DEBUG_INFORMATION_FORMAT'] = 'dwarf' if name == 'Debug' else 'dwarf-with-dsym'
        if name == 'Debug': current['SWIFT_ACTIVE_COMPILATION_CONDITIONS']='DEBUG'
        configs.append(put(label + name, '{isa = XCBuildConfiguration; ' + ('baseConfigurationReference = ' + configuration_refs[0] + '; ' if base else '') +
                           'buildSettings = ' + settings(current) + '; name = ' + name + ';}'))
    return put(label + 'configs', '{isa = XCConfigurationList; buildConfigurations = ' + listing(configs) + '; defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;}')

def phase(label, isa, files):
    return put(label, '{isa = ' + isa + '; buildActionMask = 2147483647; files = ' + listing(files) + '; runOnlyForDeploymentPostprocessing = 0;}')
project_configs=config_list('project',project_settings)
app_sources=[build_file(refs[p]) for p in all_paths if '/Widgets/' not in p]
widget_paths=[p for p in all_paths if '/Shared/' in p or '/Widgets/' in p or p.endswith('/GlassStyle.swift') or p.endswith('/KaiFont.swift')]
widget_sources=[put('widgetbuild:' + p, '{isa = PBXBuildFile; fileRef = ' + refs[p] + ';}') for p in widget_paths]
app_phases=[phase('appSources','PBXSourcesBuildPhase',app_sources),phase('appFrameworks','PBXFrameworksBuildPhase',[]),phase('appResources','PBXResourcesBuildPhase',[build_file(ref) for p, ref in zip(resource_paths, resource_refs) if p != 'Resources/LXGWWenKai-Widget.ttf'])]
widget_resource_files=[put('widgetresource:'+p, '{isa = PBXBuildFile; fileRef = ' + resource_refs[resource_paths.index(p)] + ';}') for p in ['Resources/LXGWWenKai-Widget.ttf', 'Resources/WENKAI-OFL.txt']]
widget_phases=[phase('widgetSources','PBXSourcesBuildPhase',widget_sources),phase('widgetFrameworks','PBXFrameworksBuildPhase',[]),phase('widgetResources','PBXResourcesBuildPhase',widget_resource_files)]
test_phases=[phase('testSources','PBXSourcesBuildPhase',[build_file(test_ref)]),phase('testFrameworks','PBXFrameworksBuildPhase',[]),phase('testResources','PBXResourcesBuildPhase',[])]
ui_test_phases=[phase('uiTestSources','PBXSourcesBuildPhase',[build_file(ui_test_ref)]),phase('uiTestFrameworks','PBXFrameworksBuildPhase',[]),phase('uiTestResources','PBXResourcesBuildPhase',[])]
embed_file=build_file(products['ClearClassWidgets'],'settings = {ATTRIBUTES = (CodeSignOnCopy, RemoveHeadersOnCopy,);};')
app_phases.append(put('embed', '{isa = PBXCopyFilesBuildPhase; buildActionMask = 2147483647; dstPath = ""; dstSubfolderSpec = 13; files = ' + listing([embed_file]) + '; name = "Embed App Extensions"; runOnlyForDeploymentPostprocessing = 0;}'))
project_id=identifier('projectObject'); app_id=identifier('target:ClearClass'); widget_id=identifier('target:ClearClassWidgets')
def dependency(label,target):
    proxy=put(label+'proxy','{isa = PBXContainerItemProxy; containerPortal = ' + project_id + '; proxyType = 1; remoteGlobalIDString = ' + target + ';}')
    return put(label+'dependency','{isa = PBXTargetDependency; target = ' + target + '; targetProxy = ' + proxy + ';}')
widget_dependency=dependency('embedWidget',widget_id)
app_dependency=dependency('testApp',app_id)
app_settings={'PRODUCT_NAME':'$(TARGET_NAME)','PRODUCT_BUNDLE_IDENTIFIER':'$(APP_BUNDLE_ID)','INFOPLIST_FILE':'Configuration/App-Info.plist',
              'GENERATE_INFOPLIST_FILE':'NO','CODE_SIGN_ENTITLEMENTS':'Configuration/App.entitlements','TARGETED_DEVICE_FAMILY':'1,2',
              'ASSETCATALOG_COMPILER_APPICON_NAME':'AppIcon','LD_RUNPATH_SEARCH_PATHS':'$(inherited) @executable_path/Frameworks',
              'ENABLE_TESTABILITY':'YES','SUPPORTED_PLATFORMS':'iphoneos iphonesimulator'}
widget_settings={'PRODUCT_NAME':'$(TARGET_NAME)','PRODUCT_BUNDLE_IDENTIFIER':'$(APP_BUNDLE_ID).widgets','INFOPLIST_FILE':'Configuration/Widget-Info.plist',
                 'GENERATE_INFOPLIST_FILE':'NO','CODE_SIGN_ENTITLEMENTS':'Configuration/Widget.entitlements','TARGETED_DEVICE_FAMILY':'1,2',
                 'APPLICATION_EXTENSION_API_ONLY':'YES','SKIP_INSTALL':'YES',
                 'LD_RUNPATH_SEARCH_PATHS':'$(inherited) @executable_path/Frameworks @executable_path/../../Frameworks','SUPPORTED_PLATFORMS':'iphoneos iphonesimulator'}
test_settings={'PRODUCT_NAME':'$(TARGET_NAME)','PRODUCT_BUNDLE_IDENTIFIER':'$(APP_BUNDLE_ID).tests','GENERATE_INFOPLIST_FILE':'YES','TARGETED_DEVICE_FAMILY':'1,2',
               'TEST_HOST':'$(BUILT_PRODUCTS_DIR)/ClearClass.app/ClearClass','BUNDLE_LOADER':'$(TEST_HOST)','TEST_TARGET_NAME':'ClearClass',
               'SUPPORTED_PLATFORMS':'iphoneos iphonesimulator'}
ui_test_settings={'PRODUCT_NAME':'$(TARGET_NAME)','PRODUCT_BUNDLE_IDENTIFIER':'$(APP_BUNDLE_ID).uitests','GENERATE_INFOPLIST_FILE':'YES','TARGETED_DEVICE_FAMILY':'1,2',
                  'TEST_TARGET_NAME':'ClearClass','SUPPORTED_PLATFORMS':'iphoneos iphonesimulator'}
targets=[]
for name,kind,phases,values,deps in [('ClearClass','application',app_phases,app_settings,[widget_dependency]),
                                    ('ClearClassWidgets','app-extension',widget_phases,widget_settings,[]),
                                    ('ClearClassTests','bundle.unit-test',test_phases,test_settings,[app_dependency]),
                                    ('ClearClassUITests','bundle.ui-testing',ui_test_phases,ui_test_settings,[app_dependency])]:
    target=put('target:'+name, '{isa = PBXNativeTarget; buildConfigurationList = ' + config_list(name,values) + '; buildPhases = ' + listing(phases) +
               '; buildRules = (); dependencies = ' + listing(deps) + '; name = ' + name + '; productName = ' + name + '; productReference = ' + products[name] +
               '; productType = "com.apple.product-type.' + kind + '";}')
    targets.append(target)
put('projectObject', '{isa = PBXProject; attributes = {BuildIndependentTargetsInParallel = YES; LastUpgradeCheck = 2600; TargetAttributes = {' +
    app_id + ' = {SystemCapabilities = {com.apple.ApplicationGroups.iOS = {enabled = 1;};};}; ' +
    widget_id + ' = {SystemCapabilities = {com.apple.ApplicationGroups.iOS = {enabled = 1;};};};};}; buildConfigurationList = ' + project_configs +
    '; compatibilityVersion = "Xcode 14.0"; developmentRegion = zh_CN; hasScannedForEncodings = 0; knownRegions = (zh_CN, en, Base,); mainGroup = ' + main_group +
    '; productRefGroup = ' + product_group + '; projectDirPath = ""; projectRoot = ""; targets = ' + listing(targets) + ';}')
destination=ROOT/'ClearClass.xcodeproj'
destination.mkdir(exist_ok=True)
destination.joinpath('project.pbxproj').write_text('// !$*UTF8*$!\n{archiveVersion = 1; classes = {}; objectVersion = 56; objects = {\n' +
    '\n'.join('  '+key+' = '+value+';' for key,value in objects.items()) + '\n}; rootObject = '+project_id+';}\n')
scheme='''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="2600" version="1.3">
<BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries>
<BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="APPID" BuildableName="ClearClass.app" BlueprintName="ClearClass" ReferencedContainer="container:ClearClass.xcodeproj"/></BuildActionEntry>
</BuildActionEntries></BuildAction>
<TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"><Testables><TestableReference skipped="NO"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="TESTID" BuildableName="ClearClassTests.xctest" BlueprintName="ClearClassTests" ReferencedContainer="container:ClearClass.xcodeproj"/></TestableReference><TestableReference skipped="NO"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="UITESTID" BuildableName="ClearClassUITests.xctest" BlueprintName="ClearClassUITests" ReferencedContainer="container:ClearClass.xcodeproj"/></TestableReference></Testables></TestAction>
<LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="APPID" BuildableName="ClearClass.app" BlueprintName="ClearClass" ReferencedContainer="container:ClearClass.xcodeproj"/></BuildableProductRunnable></LaunchAction>
<ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="APPID" BuildableName="ClearClass.app" BlueprintName="ClearClass" ReferencedContainer="container:ClearClass.xcodeproj"/></BuildableProductRunnable></ProfileAction>
<AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>'''.replace('APPID',app_id).replace('UITESTID',identifier('target:ClearClassUITests')).replace('TESTID',identifier('target:ClearClassTests'))
scheme_dir=destination/'xcshareddata/xcschemes'; scheme_dir.mkdir(parents=True,exist_ok=True)
(scheme_dir/'ClearClass.xcscheme').write_text(scheme)
print('Generated Xcode project:',len(all_paths),'Swift source files, app + widgets + unit tests')
