package com.vrtmv.app.data.camera

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * ARCore Session 이 외부 OES 텍스처에 그려둔 카메라 프레임을 풀스크린 쿼드로 GL 표면에
 * 출력. 디바이스 회전은 매 프레임 [Frame.transformCoordinates2d] 로 텍스처 좌표 갱신.
 */
class BackgroundRenderer {

    companion object {
        private const val TAG = "BgRenderer"
        private const val COORDS_PER_VERTEX = 2
        private const val NUM_VERTICES = 4

        // OPENGL_NORMALIZED_DEVICE_COORDINATES 풀스크린 쿼드 (TRIANGLE_STRIP: 좌하→우하→좌상→우상)
        private val QUAD_NDC = floatArrayOf(
            -1f, -1f,
            +1f, -1f,
            -1f, +1f,
            +1f, +1f
        )

        // VIEW_NORMALIZED 의 같은 4 모서리 — Y축이 NDC 와 반대(아래가 +1)이므로
        // 좌하→우하→좌상→우상 순으로 매칭. 이를 어기면 카메라 영상이 상하 반전된다.
        private val QUAD_VIEW_NORMALIZED = floatArrayOf(
            0f, 1f,  // 좌하
            1f, 1f,  // 우하
            0f, 0f,  // 좌상
            1f, 0f   // 우상
        )

        private const val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, v_TexCoord);
            }
        """
    }

    // Session 이 카메라 프레임을 그려 넣을 외부 OES 텍스처 ID.
    var textureName: Int = -1
        private set

    private var program: Int = 0
    private var aPosition: Int = 0
    private var aTexCoord: Int = 0
    private var uTexture: Int = 0

    private val quadVertices: FloatBuffer = floatBuffer(QUAD_NDC)
    // 초기값을 단위 텍스처 좌표로 설정 — transformCoordinates2d 가 PAUSED 상태에서 0 벡터를
    // 반환할 경우의 폴백 (그렇지 않으면 셰이더가 단일 픽셀만 샘플링해 화면이 검정으로 렌더됨).
    private val texCoords: FloatBuffer = floatBuffer(
        floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
    )
    private val texTransformed = FloatArray(QUAD_NDC.size)
    private var texCoordsLogged: Boolean = false

    fun createOnGlThread() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureName = textures[0]
        Log.i(TAG, "createOnGlThread: textureName=$textureName")
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureName)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(program)}")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)

        aPosition = GLES20.glGetAttribLocation(program, "a_Position")
        aTexCoord = GLES20.glGetAttribLocation(program, "a_TexCoord")
        uTexture = GLES20.glGetUniformLocation(program, "sTexture")
        Log.i(TAG, "program=$program aPosition=$aPosition aTexCoord=$aTexCoord uTexture=$uTexture")
    }

    // hasDisplayGeometryChanged() 가 첫 프레임 이후 false 만 반환하는 케이스가 있어 매 프레임 무조건 갱신.
    fun updateDisplayGeometry(frame: Frame) {
        frame.transformCoordinates2d(
            Coordinates2d.VIEW_NORMALIZED,
            QUAD_VIEW_NORMALIZED,
            Coordinates2d.TEXTURE_NORMALIZED,
            texTransformed
        )
        // PAUSED 등에서 모두 0 반환 — 그 경우 초기 단위 좌표 유지
        val allZero = texTransformed.all { it == 0f }
        if (!allZero) {
            texCoords.position(0)
            texCoords.put(texTransformed)
            texCoords.position(0)
        }
        if (!texCoordsLogged) {
            texCoordsLogged = true
            Log.i(TAG, "texTransformed=${texTransformed.joinToString(",") { "%.2f".format(it) }} allZero=$allZero")
        }
    }

    fun draw() {
        if (program == 0) return

        // depth 무시 — 항상 가장 뒤
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureName)
        GLES20.glUniform1i(uTexture, 0)

        quadVertices.position(0)
        GLES20.glVertexAttribPointer(aPosition, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, quadVertices)
        GLES20.glEnableVertexAttribArray(aPosition)

        texCoords.position(0)
        GLES20.glVertexAttribPointer(aTexCoord, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glEnableVertexAttribArray(aTexCoord)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, NUM_VERTICES)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)

        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun floatBuffer(arr: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(arr); position(0)
        }
}
