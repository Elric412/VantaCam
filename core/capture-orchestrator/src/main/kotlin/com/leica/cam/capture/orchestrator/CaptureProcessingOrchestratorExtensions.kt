package com.leica.cam.capture.orchestrator

import com.leica.cam.hardware.contracts.photon.PhotonBuffer
import com.leica.cam.hypertone_wb.pipeline.RgbFrame
import com.leica.cam.imaging_pipeline.pipeline.PipelineFrame
import com.leica.cam.photon_matrix.FusedPhotonBuffer

private const val U16_MAX_FLOAT = 65_535f

internal fun PhotonBuffer.toPipelineFrame(): PipelineFrame {
    val pixelCount = width * height
    val planeCount = planeCount()
    val redPlane = planeView(0)
    val greenPlane = planeView(if (planeCount > 1) 1 else 0)
    val bluePlane = planeView(if (planeCount > 2) 2 else if (planeCount > 1) 1 else 0)
    val red = FloatArray(pixelCount)
    val green = FloatArray(pixelCount)
    val blue = FloatArray(pixelCount)
    for (i in 0 until pixelCount) {
        red[i] = ((redPlane.get(i).toInt() and 0xffff) / U16_MAX_FLOAT).coerceIn(0f, 1f)
        green[i] = ((greenPlane.get(i).toInt() and 0xffff) / U16_MAX_FLOAT).coerceIn(0f, 1f)
        blue[i] = ((bluePlane.get(i).toInt() and 0xffff) / U16_MAX_FLOAT).coerceIn(0f, 1f)
    }
    return PipelineFrame(width, height, red, green, blue)
}

internal fun PipelineFrame.toPhotonBuffer(): PhotonBuffer = PhotonBuffer.create16Bit(
    width = width,
    height = height,
    planes = listOf(red.toU16Plane(), green.toU16Plane(), blue.toU16Plane()),
)

internal fun FusedPhotonBuffer.toRgbFrame(): RgbFrame {
    val frame = underlying.toPipelineFrame()
    return RgbFrame(
        width = frame.width,
        height = frame.height,
        red = frame.red,
        green = frame.green,
        blue = frame.blue,
    )
}

internal fun RgbFrame.toFusedPhotonBuffer(template: FusedPhotonBuffer): FusedPhotonBuffer = FusedPhotonBuffer(
    underlying = PhotonBuffer.create16Bit(
        width = width,
        height = height,
        planes = listOf(red.toU16Plane(), green.toU16Plane(), blue.toU16Plane()),
    ),
    fusionQuality = template.fusionQuality,
    frameCount = template.frameCount,
    motionMagnitude = template.motionMagnitude,
)

internal fun identitySensorToXyz3x3(): FloatArray = floatArrayOf(
    1f, 0f, 0f,
    0f, 1f, 0f,
    0f, 0f, 1f,
)

internal fun com.leica.cam.face_engine.api.SkinZoneMap.toBooleanMask(expectedSize: Int): BooleanArray? =
    if (mask.size == expectedSize) BooleanArray(mask.size) { index -> mask[index] > 0.2f } else null

private fun FloatArray.toU16Plane(): ShortArray = ShortArray(size) { index ->
    (this[index].coerceIn(0f, 1f) * U16_MAX_FLOAT).toInt().coerceIn(0, 65_535).toShort()
}
