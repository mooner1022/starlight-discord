package dev.mooner.starlightdiscord.buildlogic

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Makes `Pattern.compile(String, int)` calls in the app and its dependencies work on Android.
 *
 * Android's ICU-based java.util.regex throws `IllegalArgumentException: Unsupported flags` for
 * `UNICODE_CHARACTER_CLASS` and `CANON_EQ`. JDA compiles patterns with `UNICODE_CHARACTER_CLASS` in the static
 * initializer of `Checks`, which almost every JDA call goes through, so JDA cannot even build a client.
 * ICU already matches `\w`, `\d`, `\s` and `\b` by Unicode properties, so dropping the flag keeps the meaning.
 */
class AndroidRegexCompatPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.plugins.withId("com.android.application") {
            val components = project.extensions.getByType(AndroidComponentsExtension::class.java)
            components.onVariants(components.selector().all()) { variant ->
                variant.instrumentation.transformClassesWith(
                    PatternFlagsVisitorFactory::class.java,
                    InstrumentationScope.ALL,
                ) {}
                variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COPY_FRAMES)
            }
        }
    }
}

abstract class PatternFlagsVisitorFactory : AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            override fun visitMethod(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor =
                PatternFlagsMethodVisitor(super.visitMethod(access, name, descriptor, signature, exceptions))
        }

    override fun isInstrumentable(classData: ClassData): Boolean = true
}

private class PatternFlagsMethodVisitor(next: MethodVisitor) : MethodVisitor(Opcodes.ASM9, next) {

    private var patched = false

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
        if (opcode == Opcodes.INVOKESTATIC && owner == PATTERN && name == "compile" && descriptor == COMPILE_WITH_FLAGS) {
            // Stack: pattern, flags -> pattern, flags & SUPPORTED_FLAGS_MASK
            super.visitLdcInsn(SUPPORTED_FLAGS_MASK)
            super.visitInsn(Opcodes.IAND)
            patched = true
        }
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
        // The mask is pushed on top of the call's two arguments.
        super.visitMaxs(if (patched) maxStack + 1 else maxStack, maxLocals)
    }

    private companion object {
        const val PATTERN = "java/util/regex/Pattern"
        const val COMPILE_WITH_FLAGS = "(Ljava/lang/String;I)Ljava/util/regex/Pattern;"
        const val CANON_EQ = 0x80
        const val UNICODE_CHARACTER_CLASS = 0x100
        const val SUPPORTED_FLAGS_MASK = (CANON_EQ or UNICODE_CHARACTER_CLASS).inv()
    }
}
