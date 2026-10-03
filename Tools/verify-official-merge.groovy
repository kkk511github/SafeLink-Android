// Run with Groovy, JavaParser and the Gradle runtime jars on the classpath.
// This is source/host regression coverage, not an Android instrumentation test.
import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import groovy.xml.XmlParser

def root = new File(args ? args[0] : '.').canonicalFile
def javaRoot = new File(root, 'TMessagesProj/src/main/java/org/telegram')
def parser = new JavaParser(new ParserConfiguration()
    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_14))
def units = [:]
def source = { String path -> new File(javaRoot, path).getText('UTF-8') }
def unit = { String path ->
    if (!units.containsKey(path)) {
        def result = parser.parse(source(path))
        assert result.successful: "${path}: ${result.problems}"
        units[path] = result.result.get()
    }
    units[path]
}
def method = { String path, String name ->
    def matches = unit(path).findAll(MethodDeclaration).findAll { it.nameAsString == name }
    assert matches.size() == 1: "Expected one ${path}:${name}, got ${matches.size()}"
    matches[0]
}
def calls = { node, String name -> node.findAll(MethodCallExpr).findAll { it.nameAsString == name } }

def customSources = [
    'messenger/AuthTokensHelper.java', 'messenger/GroupPrivateChatController.java',
    'messenger/LocaleController.java', 'messenger/MessagesController.java',
    'messenger/NotificationCenter.java', 'messenger/PushListenerController.java',
    'messenger/SafeLinkServer.java', 'messenger/SafeLinkServers.java',
    'messenger/SafeLinkStrings.java', 'messenger/browser/Browser.java',
    'tgnet/ConnectionsManager.java', 'tgnet/TLRPC.java', 'ui/Adapters/MentionsAdapter.java',
    'ui/Cells/AccountSelectCell.java', 'ui/ChatActivity.java', 'ui/ChatEditActivity.java',
    'ui/ChatUsersActivity.java', 'ui/LaunchActivity.java', 'ui/LoginActivity.java',
    'ui/MainTabsActivity.java', 'ui/ProfileActivity.java', 'ui/SafeLinkServersActivity.java',
    'ui/SettingsActivity.java', 'ui/TwoStepVerificationSetupActivity.java',
    'ui/web/BotWebViewContainer.java'
]
customSources.each { unit(it) }
println "PASS: JavaParser syntax for ${customSources.size()} custom Java files"

def auth = unit('messenger/AuthTokensHelper.java')
def authMethods = auth.findAll(MethodDeclaration).collectEntries { [(it.nameAsString): it.parameters.size()] }
customSources.each { path ->
    unit(path).findAll(MethodCallExpr).findAll {
        it.scope.present && it.scope.get().toString() == 'AuthTokensHelper'
    }.each { call ->
        assert authMethods.containsKey(call.nameAsString): "Unknown token API: ${call}"
        assert call.arguments.size() == authMethods[call.nameAsString]: "Token API mismatch: ${call}"
    }
}
calls(auth, 'requestBackup').each { assert it.arguments.empty }
assert method('messenger/BackupAgent.java', 'requestBackup').parameters.empty
assert calls(method('ui/MainTabsActivity.java', 'onFragmentCreate'), 'add').any {
    it.arguments[0].toString() == 'NotificationCenter.appConfigUpdated'
}
assert method('ui/TwoStepVerificationSetupActivity.java', 'setFromRegistration').parameters.size() == 1
assert method('ui/TwoStepVerificationSetupActivity.java', 'setBlockingAlert').parameters.size() == 1
println 'PASS: token/backup APIs and registration observer integration'

def logout = method('messenger/MessagesController.java', 'performLogout')
def callback = calls(logout, 'sendRequest')[0].arguments[1].asLambdaExpr()
def callbackCalls = callback.findAll(MethodCallExpr)
def names = callbackCalls.collect { it.nameAsString }
assert names.indexOf('addLogOutToken') < names.indexOf('cleanup')
assert names.indexOf('cleanup') < names.indexOf('runOnUIThread')
assert calls(callback, 'addLogOutToken')[0].arguments[0].toString() == 'logoutServerId'
def addToken = method('messenger/AuthTokensHelper.java', 'addLogOutToken')
assert calls(addToken, 'commit').size() == 1
assert calls(addToken, 'apply').empty
assert calls(addToken, 'getSharedPreferences')[0].arguments[0].toString() == '"saved_tokens_" + serverId'
['getSavedLogOutTokens', 'getSavedLogInTokens', 'saveLogOutTokens', 'saveLogInTokens'].each { name ->
    assert calls(method('messenger/AuthTokensHelper.java', name), 'account').size() == 1
}
println 'PASS: logout persistence precedes cleanup/login; stores remain server-isolated'

// Execute the AST-extracted logout method against durable preference doubles.
def tokenLoader = new GroovyClassLoader(this.class.classLoader)
def tokenHarness = tokenLoader.parseClass('''
class Context { static final int MODE_PRIVATE = 0 }
class SharedPreferences {
    Map durable = [:]
    int commits
    int getInt(String key, int fallback) { durable.containsKey(key) ? durable[key] : fallback }
    String getString(String key, String fallback) { durable.containsKey(key) ? durable[key] : fallback }
    Editor edit() { new Editor(this) }
    static class Editor {
        SharedPreferences prefs
        Map pending = [:]
        Editor(SharedPreferences prefs) { this.prefs = prefs }
        Editor putString(String key, String value) { pending[key] = value; this }
        Editor putInt(String key, int value) { pending[key] = value; this }
        boolean commit() { prefs.durable.putAll(pending); prefs.commits++; true }
    }
}
class FakeContext {
    Map stores = [:]
    SharedPreferences getSharedPreferences(String name, int mode) {
        if (!stores.containsKey(name)) stores[name] = new SharedPreferences()
        stores[name]
    }
}
class ApplicationLoader { static FakeContext applicationContext = new FakeContext() }
class SerializedData {
    byte[] bytes
    SerializedData(int size) {}
    byte[] toByteArray() { bytes }
}
class TLRPC {
    static class TL_auth_loggedOut {
        byte[] future_auth_token
        int getObjectSize() { future_auth_token.length }
        void serializeToStream(SerializedData data) { data.bytes = future_auth_token }
    }
}
class Utilities { static String bytesToHex(byte[] bytes) { bytes.encodeHex().toString() } }
class FileLog { static void e(String error) { throw new AssertionError(error) } }
class BackupAgent { static int backups; static void requestBackup() { backups++ } }
class TokenHarness {
''' + addToken.toString() + '\n}\n', 'TokenHarness.groovy')
tokenHarness = tokenLoader.loadClass('TokenHarness')
def tokenClass = tokenLoader.loadClass('TLRPC$TL_auth_loggedOut')
def add = { String server, int value ->
    def token = tokenClass.newInstance()
    token.future_auth_token = [value] as byte[]
    tokenHarness.addLogOutToken(server, token)
}
(1..25).each { add('alpha', it) }
add('beta', 99)
def context = tokenLoader.loadClass('ApplicationLoader').applicationContext
def alpha = context.stores.saved_tokens_alpha
def beta = context.stores.saved_tokens_beta
assert alpha.durable.count == 20 && alpha.commits == 25
(0..<20).each { index ->
    assert alpha.durable["log_out_token_${index}".toString()] == String.format('%02x', 25 - index)
}
assert beta.durable.count == 1 && beta.durable.log_out_token_0 == '63'
def emptyToken = tokenClass.newInstance()
emptyToken.future_auth_token = new byte[0]
tokenHarness.addLogOutToken('alpha', emptyToken)
emptyToken.future_auth_token = null
tokenHarness.addLogOutToken('alpha', emptyToken)
assert alpha.commits == 25 && tokenLoader.loadClass('BackupAgent').backups == 26
alpha.durable.count = -100
add('alpha', 42)
assert alpha.durable.count == 1 && alpha.durable.log_out_token_0 == '2a'
println 'PASS: executed logout ring (20 newest), synchronous commits, invalid tokens and server isolation'

// Execute the unchanged helper source with Groovy's JVM compiler and a resource stub.
def loader = new GroovyClassLoader(this.class.classLoader)
loader.parseClass('''package org.telegram.messenger
class R {
    static class string {
        static final int SafeLinkGroupPrivateChatForbidden = 101
        static final int SafeLinkGroupPrivateChatForbiddenToast = 102
    }
}
''')
def stringsClass = loader.parseClass(source('messenger/SafeLinkStrings.java'), 'SafeLinkStrings.groovy')
def override = stringsClass.getDeclaredMethod('chineseOverride', String, Integer.TYPE, String)
override.accessible = true
['zh', 'zh-cn', 'zh_CN', 'zh-Hant', 'zh-tw', 'zh_HK', 'zh-MO'].each { language ->
    [
        ['SafeLinkGroupPrivateChatForbidden', 101],
        ['SafeLinkGroupPrivateChatForbiddenToast', 102]
    ].each { entry ->
        def keyed = override.invoke(null, entry[0], 0, language)
        def resourceOnly = override.invoke(null, null, entry[1], language)
        assert keyed != null && keyed == resourceOnly
    }
}
assert override.invoke(null, null, 102, 'zh') != override.invoke(null, null, 102, 'zh-Hant')
assert override.invoke(null, null, 101, 'en') == null
assert override.invoke(null, null, 101, null) == null
assert override.invoke(null, 'unrelated', 101, 'zh') == null
assert override.invoke(null, null, 999, 'zh') == null
def v2 = method('messenger/LocaleController.java', 'getStringV2')
assert calls(v2, 'chineseOverride').size() == 2
assert calls(v2, 'chineseOverride').every { it.arguments[1].toString() == 'stringRes' }
assert calls(v2, 'chineseOverride')[0].range.get().begin.line < calls(v2, 'getByResNameOrResId')[0].range.get().begin.line
println 'PASS: keyed/resource-only Chinese overrides, base-language fallback and cloud precedence'

def xmlParser = new XmlParser(false, false)
def resources = new File(root, 'TMessagesProj/src/main/res')
def resourceFiles = []
resources.eachDir { directory ->
    if (directory.name.startsWith('values')) {
        directory.eachFile { file ->
            if (file.name.startsWith('strings') && file.name.endsWith('.xml')) resourceFiles.add(file)
        }
    }
}
def hashes = [:]
resourceFiles.each { file ->
    def xml = xmlParser.parse(file)
    def entries = xml.string
    def keys = entries.collect { it.attribute('name') }
    assert keys.toSet().size() == keys.size(): "Duplicate string in ${file}"
    entries.each { entry ->
        String key = entry.attribute('name')
        int hash = key.hashCode()
        assert !hashes.containsKey(hash) || hashes[hash] == key: "Localization hash collision: ${key}"
        hashes[hash] = key
        if (key in ['AppName', 'AppNameBeta']) assert entry.text() == 'SafeLink': "Brand regression in ${file}"
    }
}
['TelegramBuildAppPlugin.kt', 'TelegramBuildPlugin.kt'].each { name ->
    assert new File(root, "buildSrc/src/main/kotlin/org/telegram/plugin/${name}").text.contains('include("values-*/strings_safelink.xml")')
}
['values-zh', 'values-zh-rTW', 'values-b+zh+Hant'].each { directory ->
    assert xmlParser.parse(new File(resources, "${directory}/strings_safelink.xml")).string.size() == 2
}
println "PASS: ${resourceFiles.size()} resource XMLs, string hashes, SafeLink brand and Chinese inputs"

assert method('messenger/GroupPrivateChatController.java', 'shouldBlockPrivateChatFromGroup').parameters.size() == 3
['ui/ChatActivity.java', 'ui/ChatUsersActivity.java', 'ui/ProfileActivity.java'].each { path ->
    assert calls(unit(path), 'shouldBlockPrivateChatFromGroup').every { it.arguments.size() == 3 }
    assert !calls(unit(path), 'shouldBlockPrivateChatFromGroup').empty
}
assert calls(unit('ui/Adapters/MentionsAdapter.java'), 'canMentionParticipant').size() == 3
['TL_safelink_getGroupPrivateChatForbidden', 'TL_safelink_toggleGroupPrivateChatForbidden'].each { name ->
    assert unit('tgnet/TLRPC.java').getClassByName('TLRPC').get().getMembers().any {
        it.isClassOrInterfaceDeclaration() && it.asClassOrInterfaceDeclaration().nameAsString == name
    }
}
println 'PASS: group-private-chat entry points and custom RPC declarations'

// Optional: execute the real Kotlin generators after a narrow host compilation.
if (args.contains('--localization')) {
    def output = new File(root, 'build/merge-validation')
    def projectDir = new File(output, 'project')
    def gradleHome = new File(output, 'gradle-home')
    projectDir.mkdirs()
    gradleHome.mkdirs()
    def project = org.gradle.testfixtures.ProjectBuilder.builder()
        .withProjectDir(projectDir).withGradleUserHomeDir(gradleHome).build()
    def inputs = resourceFiles.findAll { it.parentFile.name != 'values' }
    def defaults = resourceFiles.findAll { it.parentFile.name == 'values' }
    def task = project.tasks.create('verifyStrings',
        Class.forName('org.telegram.tasks.TelegramStringsTask'))
    task.stringsXml.from(defaults)
    task.localizationFiles.from(inputs)
    task.resourcePackageName.set('org.telegram.messenger')
    task.stringsOutputDir.set(new File(output, 'generated/res'))
    task.assetsOutputDir.set(new File(output, 'generated/assets'))
    task.stableIdsFile.set(new File(output, 'generated/stable-ids.txt'))
    task.generate()
    def utils = project.tasks.create('verifyLocalizationUtils',
        Class.forName('org.telegram.tasks.localization.GenerateLocalizationUtilsJavaTask'))
    utils.localizationFiles.from(inputs)
    utils.javaOutputDir.set(new File(output, 'generated/java'))
    utils.generate()

    def readLocalization = { String tag ->
        def file = new File(output, "generated/assets/localization_${tag}.bin")
        assert file.isFile(): "Missing asset: ${file}"
        def decoded = [:]
        file.withDataInputStream { stream ->
            int count = Integer.reverseBytes(stream.readInt())
            count.times {
                int hash = Integer.reverseBytes(stream.readInt())
                int length = stream.readUnsignedByte()
                int header = 1
                if (length == 254) {
                    length = stream.readUnsignedByte() | (stream.readUnsignedByte() << 8) | (stream.readUnsignedByte() << 16)
                    header = 4
                }
                byte[] bytes = new byte[length]
                stream.readFully(bytes)
                int padding = (4 - ((header + length) % 4)) % 4
                padding.times { assert stream.readUnsignedByte() == 0 }
                assert !decoded.containsKey(hash)
                decoded[hash] = new String(bytes, 'UTF-8')
            }
            assert stream.read() == -1
        }
        decoded
    }
    ['zh': 'values-zh', 'zh_tw': 'values-zh-rTW', 'zh_hant': 'values-b+zh+Hant'].each { tag, directory ->
        def decoded = readLocalization(tag)
        xmlParser.parse(new File(resources, "${directory}/strings_safelink.xml")).string.each { entry ->
            assert decoded[entry.attribute('name').hashCode()] == entry.text()
        }
        assert new File(output, 'generated/java/org/telegram/localization/LocalizationUtils.java')
            .text.contains("localization_${tag}.bin")
    }
    assert readLocalization('en')['AppName'.hashCode()] == 'SafeLink'
    assert readLocalization('en')['AppNameBeta'.hashCode()] == 'SafeLink'
    println 'PASS: real Kotlin generators and decoded English/Chinese binary assets'
}
println 'All host/source regression checks passed. Android runtime coverage is still required.'
