package com.leica.cam.hypertone_wb.pipeline

import kotlin.math.abs

class IlluminantEstimators(
    val global: IlluminantPredictor,
    val local: IlluminantPredictor,
    val semantic: IlluminantPredictor,
)
