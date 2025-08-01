package maestro.js

import maestro.utils.HttpClient
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.time.Duration.Companion.minutes

private val NULL_HANDLER = object : Handler() {
    override fun publish(record: LogRecord?) {}

    override fun flush() {}

    override fun close() {}
}

class GraalJsEngine(
    httpClient: OkHttpClient = HttpClient.build(
        name = "GraalJsEngine",
        readTimeout = 5.minutes,
        writeTimeout = 5.minutes,
        protocols = listOf(Protocol.HTTP_1_1)
    ),
    platform: String = "unknown",
) : JsEngine {

    private val openContexts = HashSet<Context>()

    private val httpBinding = GraalJsHttp(httpClient)
    private val outputBinding = HashMap<String, Any>()
    private val maestroBinding = HashMap<String, Any?>()
    private val envBinding = HashMap<String, String>()
    
    // Stack to track environment variable scopes for proper isolation
    private val envScopeStack = mutableListOf<HashMap<String, String>>()

    private var onLogMessage: (String) -> Unit = {}

    private var platform = platform

    override fun close() {
        openContexts.forEach { it.close() }
    }

    override fun onLogMessage(callback: (String) -> Unit) {
        onLogMessage = callback
    }

    override fun enterScope() {}

    override fun leaveScope() {}

    override fun putEnv(key: String, value: String) {
        this.envBinding[key] = value
    }

    override fun setCopiedText(text: String?) {
        this.maestroBinding["copiedText"] = text
    }

    override fun evaluateScript(
        script: String,
        env: Map<String, String>,
        sourceName: String,
        runInSubScope: Boolean,
    ): Value {
        envBinding.putAll(env)
        val source = Source.newBuilder("js", script, sourceName).build()
        return createContext().eval(source)
    }

    private fun createContext(): Context {
        val outputStream = object : ByteArrayOutputStream() {
            override fun flush() {
                super.flush()
                val log = toByteArray().decodeToString().removeSuffix("\n")
                onLogMessage(log)
                reset()
            }
        }

        val context = Context.newBuilder("js")
            .option("js.strict", "true")
            .logHandler(NULL_HANDLER)
            .out(outputStream)
            .build()

        openContexts.add(context)

        envBinding.forEach { (key, value) -> context.getBindings("js").putMember(key, value) }

        context.getBindings("js").putMember("http", httpBinding)
        context.getBindings("js").putMember("output", ProxyObject.fromMap(outputBinding))
        context.getBindings("js").putMember("maestro", ProxyObject.fromMap(maestroBinding))

        maestroBinding["platform"] = platform

        context.eval(
            "js", """
            // Prevent a reference error on referencing undeclared variables. Enables patterns like {MY_ENV_VAR || 'default-value'}.
            // Instead of throwing an error, undeclared variables will evaluate to undefined.
            Object.setPrototypeOf(globalThis, new Proxy(Object.prototype, {
                has(target, key) {
                    return true;
                }
            }))
            function json(text) {
                return JSON.parse(text)
            }
            function relativePoint(x, y) {
                var xPercent = Math.ceil(x * 100) + '%'
                var yPercent = Math.ceil(y * 100) + '%'
                return xPercent + ',' + yPercent
            }
        """.trimIndent()
        )

        return context
    }

    override fun enterEnvScope() {
        // Create a new environment variable scope for flow isolation.
        // For GraalJS, we manually manage environment variable scoping by
        // saving the current environment state to a stack before allowing
        // new variables to be added or existing ones to be overridden.
        envScopeStack.add(HashMap(envBinding))
    }

    override fun leaveEnvScope() {
        // Restore previous environment state
        if (envScopeStack.isNotEmpty()) {
            val previousEnv = envScopeStack.removeAt(envScopeStack.size - 1)
            envBinding.clear()
            envBinding.putAll(previousEnv)
        }
    }
}
