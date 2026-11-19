package net.oh4ma.advancehotspot.ui.components

import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import android.graphics.RenderEffect as PlatformRenderEffect

/**
 * 液態玻璃的外觀參數，長度單位都是像素。
 *
 * 概念取自 FlClash（prism_ui 的 LiquidGlassStyle），以單一 AGSL shader 近似：
 * 邊緣折射、放大、色散、飽和度與亮度、染色、邊緣高光，以及觸碰處的光暈。
 */
data class GlassStyle(
    val tint: Color,
    val blur: Float = 0f,
    /** 邊緣折射的深度；正值往內取樣，負值往外。 */
    val refraction: Float = 0f,
    /** 折射作用的邊緣寬度。 */
    val bevel: Float = 1f,
    val magnify: Float = 1f,
    /** 紅藍兩色折射深度相差的比例，產生邊緣色散。 */
    val dispersion: Float = 0f,
    val saturation: Float = 1f,
    val brightness: Float = 1f,
    val rim: Float = 0f,
    val rimWidth: Float = 1f,
    val opacity: Float = 1f,
)

/** 觸碰光暈：中心、強度、半徑，以及平均灑在整片玻璃上的比例。 */
data class GlassGlow(val center: Offset = Offset.Zero, val strength: Float = 0f, val reach: Float = 1f, val wash: Float = 0f)

/** 一個可重複使用的液態玻璃效果；每次繪製時依參數產生新的 RenderEffect。 */
class LiquidGlassEffect {
    private val shader = RuntimeShader(GLASS_SHADER)

    /**
     * @param shapeOrigin 玻璃形狀在圖層內的左上角
     * @param shapeSize 玻璃形狀的大小；形狀固定是膠囊
     */
    fun renderEffect(style: GlassStyle, shapeOrigin: Offset, shapeSize: Size, glow: GlassGlow = GlassGlow()): RenderEffect {
        shader.setFloatUniform("shapeOrigin", shapeOrigin.x, shapeOrigin.y)
        shader.setFloatUniform("shapeSize", shapeSize.width, shapeSize.height)
        shader.setFloatUniform("refraction", style.refraction)
        shader.setFloatUniform("bevel", style.bevel.coerceAtLeast(1f))
        shader.setFloatUniform("magnify", style.magnify.coerceAtLeast(0.01f))
        shader.setFloatUniform("dispersion", style.dispersion)
        shader.setFloatUniform("saturation", style.saturation)
        shader.setFloatUniform("brightness", style.brightness)
        shader.setFloatUniform("tint", style.tint.red, style.tint.green, style.tint.blue, style.tint.alpha)
        shader.setFloatUniform("rim", style.rim)
        shader.setFloatUniform("rimWidth", style.rimWidth.coerceAtLeast(0.01f))
        shader.setFloatUniform("glow", glow.center.x, glow.center.y, glow.strength, glow.reach)
        shader.setFloatUniform("glowWash", glow.wash)
        shader.setFloatUniform("opacity", style.opacity)
        val glass = PlatformRenderEffect.createRuntimeShaderEffect(shader, "content")
        val effect = if (style.blur > 0f) {
            // 先模糊背景再折射
            PlatformRenderEffect.createChainEffect(
                glass,
                PlatformRenderEffect.createBlurEffect(style.blur, style.blur, Shader.TileMode.CLAMP),
            )
        } else {
            glass
        }
        return effect.asComposeRenderEffect()
    }
}

private const val GLASS_SHADER = """
uniform shader content;
uniform float2 shapeOrigin;
uniform float2 shapeSize;
uniform float refraction;
uniform float bevel;
uniform float magnify;
uniform float dispersion;
uniform float saturation;
uniform float brightness;
uniform float4 tint;
uniform float rim;
uniform float rimWidth;
uniform float4 glow;
uniform float glowWash;
uniform float opacity;

float roundRectDistance(float2 p, float2 halfSize, float radius) {
    float2 q = abs(p) - halfSize + radius;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

float2 roundRectNormal(float2 p, float2 halfSize, float radius) {
    float2 q = abs(p) - halfSize + radius;
    float2 g;
    if (q.x > 0.0 && q.y > 0.0) {
        g = normalize(q);
    } else if (q.x > q.y) {
        g = float2(1.0, 0.0);
    } else {
        g = float2(0.0, 1.0);
    }
    return float2(p.x < 0.0 ? -g.x : g.x, p.y < 0.0 ? -g.y : g.y);
}

half4 main(float2 coord) {
    float2 halfSize = shapeSize * 0.5;
    float2 center = shapeOrigin + halfSize;
    float2 p = coord - center;
    float radius = min(halfSize.x, halfSize.y);
    float d = roundRectDistance(p, halfSize, radius);
    float coverage = clamp(0.5 - d, 0.0, 1.0);
    if (coverage <= 0.0) {
        return half4(0.0);
    }
    float inside = max(-d, 0.0);
    float t = clamp(1.0 - inside / bevel, 0.0, 1.0);
    // 圓弧斷面：越靠近邊緣彎折越急
    float profile = 1.0 - sqrt(1.0 - t * t);
    float2 n = roundRectNormal(p, halfSize, radius);
    float2 base = center + p / magnify;
    float2 shift = n * refraction * profile;
    float3 color;
    if (dispersion > 0.0) {
        color.r = float(content.eval(base - shift * (1.0 + dispersion)).r);
        color.g = float(content.eval(base - shift).g);
        color.b = float(content.eval(base - shift * (1.0 - dispersion)).b);
    } else {
        color = float3(content.eval(base - shift).rgb);
    }
    float luma = dot(color, float3(0.2126, 0.7152, 0.0722));
    color = mix(float3(luma), color, saturation) * brightness;
    color = mix(color, tint.rgb, tint.a);
    // 邊緣高光，上下緣較亮
    float edge = 1.0 - smoothstep(0.0, rimWidth, inside);
    color += rim * edge * (0.35 + 0.65 * abs(n.y));
    float g = distance(coord, glow.xy) / max(glow.w, 1.0);
    color += glow.z * (exp(-g * g) * (1.0 - glowWash) + glowWash);
    color = clamp(color, 0.0, 1.0);
    return half4(half3(color), 1.0) * half(coverage * opacity);
}
"""
