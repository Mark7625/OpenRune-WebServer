package dev.openrune.cache.extractor.osrs.map

import dev.openrune.OsrsCacheProvider
import dev.openrune.cache.OBJECT
import dev.openrune.cache.extractor.osrs.map.JagexColor.unpackHue
import dev.openrune.cache.extractor.osrs.map.JagexColor.unpackLuminance
import dev.openrune.cache.extractor.osrs.map.JagexColor.unpackSaturation
import dev.openrune.cache.extractor.osrs.map.region.Location
import dev.openrune.cache.extractor.osrs.map.region.Position
import dev.openrune.cache.extractor.osrs.map.region.Region
import dev.openrune.cache.extractor.osrs.map.region.RegionLoader
import dev.openrune.cache.filestore.definition.ConfigDefinitionDecoder
import dev.openrune.cache.filestore.definition.DefinitionTransform
import dev.openrune.cache.filestore.definition.SpriteDecoder
import dev.openrune.definition.codec.ObjectCodec
import dev.openrune.definition.game.IndexedSprite
import dev.openrune.definition.type.*
import dev.openrune.filesystem.Cache
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.IOException
import java.util.*
import kotlin.experimental.and


class ObjectDecoder : ConfigDefinitionDecoder<ObjectType>(
    ObjectCodec(235), OBJECT,
    DefinitionTransform { id, definition -> definition.postDecode() })

class MapImageDumper(
    val store: Cache,
    val regionLoader: RegionLoader
) {
    private val wallColor =
        (238 + (random() * 20.0).toInt() - 10 shl 16) + (238 + (random() * 20.0).toInt() - 10 shl 8) + (238 + (random() * 20.0).toInt() - 10)
    private val doorColor = 238 + (random() * 20.0).toInt() - 10 shl 16

    private val underlays = mutableMapOf<Int, UnderlayType>()
    private val overlays = mutableMapOf<Int, OverlayType>()
    private val rsTextureProvider = mutableMapOf<Int, TextureType>()
    private val objectManager = mutableMapOf<Int, ObjectType>()
    private val spriteTypes = mutableMapOf<Int, SpriteType>()

    private var labelRegions = false

    private var outlineRegions = false

    private val renderMap = true

    private val renderObjects = true

    private val renderIcons = true

    private val renderWalls = true

    private val renderOverlays = true

    private val renderLabels = true

    private val transparency = false

    private val lowMemory = true


    protected fun random(): Double {
        // the client would use a random value here, but we prefer determinism
        return -1.2
    }

    @Throws(IOException::class)
    fun load(): MapImageDumper {

        OsrsCacheProvider.OverlayDecoder().load(store,overlays)
        OsrsCacheProvider.UnderlayDecoder().load(store,underlays)
        OsrsCacheProvider.TextureDecoder(235).load(store,rsTextureProvider)
        ObjectDecoder().load(store,objectManager)
        SpriteDecoder().load(store,spriteTypes)

        loadRegions()


        return this
    }

    fun drawMap(z: Int): BufferedImage {
        val minX = regionLoader.lowestX?.baseX ?: 0
        val minY = regionLoader.lowestY?.baseY ?: 0

        val maxX = (regionLoader.highestX?.baseX ?: 0) + Region.X
        val maxY = (regionLoader.highestY?.baseY ?: 0) + Region.Y

        val dimX = maxX - minX
        val dimY = maxY - minY

        val pixelsX: Int = dimX * MAP_SCALE
        val pixelsY: Int = dimY * MAP_SCALE

        val image = BufferedImage(
            pixelsX,
            pixelsY,
            if (transparency) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        )

        drawMap(image, z)
        drawObjects(image, z)

        return image
    }

    private fun drawNeighborObjects(image: BufferedImage, rx: Int, ry: Int, dx: Int, dy: Int, z: Int) {
        val neighbor = regionLoader.findRegionForRegionCoordinates(rx + dx, ry + dy)
        if (neighbor == null) {
            return
        }

        drawObjects(image, Region.X * dx, Region.Y * -dy, neighbor, z)
    }

    fun setScale(scale : Int) {
        MAP_SCALE = scale
        TILE_SHAPE_2D = null
    }

    fun drawRegion(region: Region, z: Int): BufferedImage {
        val pixelsX: Int = Region.X * MAP_SCALE
        val pixelsY: Int = Region.Y * MAP_SCALE

        val image = BufferedImage(
            pixelsX,
            pixelsY,
            if (transparency) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        )

        drawMap(image, 0, 0, z, region)

        drawNeighborObjects(image, region.regionX, region.regionY, -1, -1, z)
        drawNeighborObjects(image, region.regionX, region.regionY, -1, 0, z)
        drawNeighborObjects(image, region.regionX, region.regionY, -1, 1, z)
        drawNeighborObjects(image, region.regionX, region.regionY, 0, -1, z)
        drawObjects(image, 0, 0, region, z)
        drawNeighborObjects(image, region.regionX, region.regionY, 0, 1, z)
        drawNeighborObjects(image, region.regionX, region.regionY, 1, -1, z)
        drawNeighborObjects(image, region.regionX, region.regionY, 1, 0, z)
        drawNeighborObjects(image, region.regionX, region.regionY, 1, 1, z)

        return image
    }

    private fun drawMap(image: BufferedImage, drawBaseX: Int, drawBaseY: Int, z: Int, region: Region) {
        if (!renderMap) {
            return
        }

        val map = Array<Array<IntArray?>?>(4) { null }

        for (x in 0..<Region.X) {
            for (y in 0..<Region.Y) {
                val isBridge = (region.getTileSetting(1, x, Region.Y - y - 1).toInt() and 2) != 0
                val tileZ = z + (if (isBridge) 1 else 0)
                if (tileZ >= Region.Z) {
                    continue
                }

                val tileSetting = region.getTileSetting(z, x, Region.Y - y - 1).toInt()
                if ((tileSetting and 24) == 0) {
                    if (z == 0 && isBridge) {
                        drawTile(image, map, region, drawBaseX, drawBaseY, 0, x, y)
                    }
                    drawTile(image, map, region, drawBaseX, drawBaseY, tileZ, x, y)
                }

                if (tileZ < 3) {
                    val upTileSetting = region.getTileSetting(z + 1, x, Region.Y - y - 1).toInt()
                    if ((upTileSetting and 8) != 0) {
                        drawTile(image, map, region, drawBaseX, drawBaseY, tileZ + 1, x, y)
                    }
                }
            }
        }
    }

    private fun drawMap(image: BufferedImage, z: Int) {
        val lowestXBase = regionLoader.lowestX?.baseX ?: 0
        val highestYBase = regionLoader.highestY?.baseY ?: 0
        
        for (region in regionLoader.regions) {
            val baseX = region.baseX
            val baseY = region.baseY

            // to pixel X
            val drawBaseX = baseX - lowestXBase

            // to pixel Y. top most y is 0, but the top most
            // region has the greatest y, so invert
            val drawBaseY = highestYBase - baseY

            drawMap(image, drawBaseX, drawBaseY, z, region)
        }
    }

    private fun drawTile(
        to: BufferedImage,
        planes: Array<Array<IntArray?>?>,
        region: Region,
        drawBaseX: Int,
        drawBaseY: Int,
        z: Int,
        x: Int,
        y: Int
    ) {
        var pixels = planes[z]

        if (pixels == null) {
            planes[z] = Array<IntArray?>(Region.X * MAP_SCALE) { IntArray(Region.Y * MAP_SCALE) }
            pixels = planes[z]
            drawMap(pixels!!, region, z)
        }

        for (i in 0..<MAP_SCALE) {
            for (j in 0..<MAP_SCALE) {
                val argb = pixels[x * MAP_SCALE + i]!![y * MAP_SCALE + j]
                if (argb != 0) {
                    to.setRGB(
                        drawBaseX * MAP_SCALE + x * MAP_SCALE + i,
                        drawBaseY * MAP_SCALE + y * MAP_SCALE + j,
                        argb
                    )
                }
            }
        }
    }

    private fun drawMap(pixels: Array<IntArray?>, region: Region, z: Int) {
        if (TILE_SHAPE_2D == null) {
            generateTileShapes()
        }

        val baseX = region.baseX
        val baseY = region.baseY

        val len: Int = Region.X + BLEND * 2
        val hues = IntArray(len)
        val sats = IntArray(len)
        val light = IntArray(len)
        val mul = IntArray(len)
        val num = IntArray(len)

        val hasLeftRegion = regionLoader.findRegionForWorldCoordinates(baseX - 1, baseY) != null
        val hasRightRegion = regionLoader.findRegionForWorldCoordinates(baseX + Region.X, baseY) != null
        val hasUpRegion = regionLoader.findRegionForWorldCoordinates(baseX, baseY + Region.Y) != null
        val hasDownRegion = regionLoader.findRegionForWorldCoordinates(baseX, baseY - 1) != null

        for (xi in (if (hasLeftRegion) -BLEND * 2 else -BLEND)..<Region.X + (if (hasRightRegion) BLEND * 2 else BLEND)) {
            for (yi in (if (hasDownRegion) -BLEND else 0)..<Region.Y + (if (hasUpRegion) BLEND else 0)) {
                val xr: Int = xi + BLEND
                if (xr >= (if (hasLeftRegion) -BLEND else 0) && xr < Region.X + (if (hasRightRegion) BLEND else 0)) {
                    val r = regionLoader.findRegionForWorldCoordinates(baseX + xr, baseY + yi)
                    if (r != null) {
                        val underlayId = r.getUnderlayId(z, convert(xr), convert(yi))
                        if (underlayId > 0) {
                            val underlay = findUnderlay(underlayId - 1)
                            hues[yi + BLEND] += underlay.hue * 256 / underlay.hueMultiplier
                            sats[yi + BLEND] += underlay.saturation
                            light[yi + BLEND] += underlay.lightness
                            mul[yi + BLEND] += underlay.hueMultiplier
                            num[yi + BLEND]++
                        }
                    }
                }

                val xl: Int = xi - BLEND
                if (xl >= (if (hasLeftRegion) -BLEND else 0) && xl < Region.X + (if (hasRightRegion) BLEND else 0)) {
                    val r = regionLoader.findRegionForWorldCoordinates(baseX + xl, baseY + yi)
                    if (r != null) {
                        val underlayId = r.getUnderlayId(z, convert(xl), convert(yi))
                        if (underlayId > 0) {
                            val underlay = findUnderlay(underlayId - 1)
                            hues[yi + BLEND] -= underlay.hue * 256 / underlay.hueMultiplier
                            sats[yi + BLEND] -= underlay.saturation
                            light[yi + BLEND] -= underlay.lightness
                            mul[yi + BLEND] -= underlay.hueMultiplier
                            num[yi + BLEND]--
                        }
                    }
                }
            }

            if (xi >= 0 && xi < Region.X) {
                var runningHues = 0
                var runningSat = 0
                var runningLight = 0
                var runningMultiplier = 0
                var runningNumber = 0

                for (yi in (if (hasDownRegion) -BLEND * 2 else -BLEND)..<Region.Y + (if (hasUpRegion) BLEND * 2 else BLEND)) {
                    val yu: Int = yi + BLEND
                    if (yu >= (if (hasDownRegion) -BLEND else 0) && yu < Region.Y + (if (hasUpRegion) BLEND else 0)) {
                        runningHues += hues[yu + BLEND]
                        runningSat += sats[yu + BLEND]
                        runningLight += light[yu + BLEND]
                        runningMultiplier += mul[yu + BLEND]
                        runningNumber += num[yu + BLEND]
                    }

                    val yd: Int = yi - BLEND
                    if (yd >= (if (hasDownRegion) -BLEND else 0) && yd < Region.Y + (if (hasUpRegion) BLEND else 0)) {
                        runningHues -= hues[yd + BLEND]
                        runningSat -= sats[yd + BLEND]
                        runningLight -= light[yd + BLEND]
                        runningMultiplier -= mul[yd + BLEND]
                        runningNumber -= num[yd + BLEND]
                    }

                    if (yi >= 0 && yi < Region.Y) {
                        val r = regionLoader.findRegionForWorldCoordinates(baseX + xi, baseY + yi)
                        if (r != null) {
                            val underlayId = r.getUnderlayId(z, convert(xi), convert(yi))
                            val overlayId = r.getOverlayId(z, convert(xi), convert(yi))

                            if (underlayId > 0 || overlayId > 0) {
                                var underlayHsl = -1
                                if (underlayId > 0) {
                                    val avgHue = runningHues / runningNumber
                                    val avgSat = runningSat / runningNumber
                                    var avgLight = runningLight / runningNumber

                                    // randomness is added to avgHue here

                                    //Doesn't seem to get hit
                                    if (avgLight < 0) {
                                        avgLight = 0
                                    } else if (avgLight > 255) {
                                        avgLight = 255
                                    }

                                    underlayHsl = packHslFull(avgHue, avgSat, avgLight)
                                }

                                var underlayRgb = 0
                                if (underlayHsl != -1) {
                                    underlayRgb = JagexColor.getRGBFull(underlayHsl)
                                }

                                var shape: Int
                                var rotation: Int
                                var overlayRgb = 0
                                if (overlayId == 0) {
                                    rotation = 0
                                    shape = rotation
                                } else {
                                    shape = r.getOverlayPath(z, convert(xi), convert(yi)) + 1
                                    rotation = r.getOverlayRotation(z, convert(xi), convert(yi)).toInt()

                                    val overlayDefinition = findOverlay(overlayId - 1)
                                    val overlayTexture = overlayDefinition.texture
                                    var hsl: Int

                                    if (overlayTexture >= 0) {
                                        hsl = rsTextureProvider[overlayTexture]?.averageRgb ?: 0
                                        //Repack it into full hsl
                                        hsl = packHslFull(
                                            unpackHue(hsl.toShort()) * 4,
                                            unpackSaturation(hsl.toShort()) * 32,
                                            unpackLuminance(hsl.toShort()) * 2
                                        )
                                    } else if (overlayDefinition.primaryRgb == 0xFF00FF) {
                                        hsl = -2
                                    } else {
                                        // randomness added here
                                        //Overlay Hsl
                                        hsl = packHslFull(
                                            overlayDefinition.hue,
                                            overlayDefinition.saturation,
                                            overlayDefinition.lightness
                                        )
                                    }

                                    if (hsl != -2) {
                                        hsl = adjustHSLListness0(hsl)
                                        overlayRgb = JagexColor.getRGBFull(hsl)
                                    }

                                    //Water, some cliffs, some buildings
                                    if (overlayDefinition.secondaryRgb != -1) {
                                        val hue = overlayDefinition.secondaryHue
                                        val sat = overlayDefinition.secondarySaturation
                                        val olight = overlayDefinition.secondaryLightness
                                        hsl = packHslFull(hue, sat, olight)
                                        overlayRgb = JagexColor.getRGBFull(hsl)
                                    }
                                }

                                if (!renderOverlays) {
                                    overlayRgb = underlayRgb
                                }

                                if (shape == 0) {
                                    val drawX = xi
                                    val drawY = Region.Y - 1 - yi
                                    if (underlayRgb != 0) {
                                        drawMapSquare(pixels, drawX, drawY, underlayRgb)
                                    }
                                } else if (shape == 1) {
                                    val drawX = xi
                                    val drawY = Region.Y - 1 - yi
                                    drawMapSquare(pixels, drawX, drawY, overlayRgb)
                                } else {
                                    shape-- //This is how jagex does it.
                                    val drawX: Int = xi * MAP_SCALE
                                    val drawY: Int = (Region.Y - 1 - yi) * MAP_SCALE
                                    rotation = convertTileRotation(rotation, shape)
                                    shape = convertTileShape(shape)
                                    if (underlayRgb != 0) {
                                        // Blending between ground types
                                        var idx = 0
                                        for (iy in 0 until MAP_SCALE) {
                                            for (ix in 0 until MAP_SCALE) {
                                                val shapeArray = TILE_SHAPE_2D?.getOrNull(shape - 1)?.getOrNull(rotation)
                                                val p = if (shapeArray != null) {
                                                    val value = if (idx < shapeArray.size) shapeArray[idx++].toInt() else {
                                                        shapeArray.last().toInt() // clip to last valid index
                                                    }
                                                    if (value == 0) underlayRgb else overlayRgb
                                                } else {
                                                    overlayRgb
                                                }
                                                setPixels(pixels, drawX + ix, drawY + iy, p)
                                            }
                                        }
                                    } else {
                                        // Only draw the overlay portion of the tile
                                        var rotIdx = 0
                                        for (iy in 0 until MAP_SCALE) {
                                            for (ix in 0 until MAP_SCALE) {
                                                val shapeArray = TILE_SHAPE_2D?.getOrNull(shape - 1)?.getOrNull(rotation)
                                                if (shapeArray != null) {
                                                    val value = if (rotIdx < shapeArray.size) shapeArray[rotIdx++] else {
                                                        println("Warning: rotIdx $rotIdx out of bounds for shapeArray.size ${shapeArray.size}")
                                                        shapeArray.last() // clip
                                                    }
                                                    if (value.toInt() != 0) {
                                                        setPixels(pixels, drawX + ix, drawY + iy, overlayRgb)
                                                    }
                                                } else {
                                                    println("Warning: TILE_SHAPE_2D missing data for shape ${shape - 1} rotation $rotation")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setPixels(pixels: Array<IntArray?>, x: Int, y: Int, value: Int) {
        if (x >= 0 && y >= 0 && x < pixels.size && y < pixels[x]!!.size) {
            pixels[x]!![y] = value
        } else {
            System.err.println("Ground drawing out of bounds! $x, $y")
        }
    }

    private fun convertTileRotation(rotation: Int, shape: Int): Int = when (shape) {
        9 -> (rotation + 1) and 3
        10, 11 -> (rotation + 3) and 3
        else -> rotation
    }

    fun convertTileShape(shape: Int): Int = when (shape) {
        9, 10 -> 1
        11 -> 8
        else -> shape
    }

    private fun generateTileShapes() {
        TILE_SHAPE_2D = Array(8) { arrayOfNulls<ByteArray>(4) }
        genTileShapes1()
        genTileShapes2()
        genTileShapes3()
        genTileShapes4()
        genTileShapes5()
        genTileShapes6()
        genTileShapes7()
        genTileShapes8()
    }

    private fun genTileShapes1() {
        var index = 0
        var shape = ByteArray(MAP_SCALE * MAP_SCALE)

        for (x in 0 until MAP_SCALE) {
            for (y in 0 until MAP_SCALE) {
                if (y <= x) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![0]!![0] = shape

        index = 0
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        for (x in MAP_SCALE - 1 downTo 0) {
            for (y in 0 until MAP_SCALE) {
                if (y <= x) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![0]!![1] = shape

        index = 0
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        for (x in 0 until MAP_SCALE) {
            for (y in 0 until MAP_SCALE) {
                if (y >= x) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![0]!![2] = shape

        index = 0
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        for (x in MAP_SCALE - 1 downTo 0) {
            for (y in 0 until MAP_SCALE) {
                if (y >= x) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![0]!![3] = shape
    }

    fun genTileShapes2() {
        var index = 0
        var shape = ByteArray(MAP_SCALE * MAP_SCALE)

        for (x in MAP_SCALE - 1 downTo 0) {
            for (y in 0 until MAP_SCALE) {
                if (y <= x shr 1) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![1]!![0] = shape

        index = 0
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        for (x in 0 until MAP_SCALE) {
            for (y in 0 until MAP_SCALE) {
                if (y >= x shl 1) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![1]!![1] = shape

        index = 0
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        for (x in 0 until MAP_SCALE) {
            for (y in MAP_SCALE - 1 downTo 0) {
                if (y <= x shr 1) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![1]!![2] = shape

        index = 0
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        for (x in MAP_SCALE - 1 downTo 0) {
            for (y in MAP_SCALE - 1 downTo 0) {
                if (y >= x shl 1) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![1]!![3] = shape
    }

    fun genTileShapes3() {
        var index = 0
        var shape = ByteArray(MAP_SCALE * MAP_SCALE)

        for (x in MAP_SCALE - 1 downTo 0) {
            for (y in MAP_SCALE - 1 downTo 0) {
                if (y <= x shr 1) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![2]!![0] = shape

        index = 0
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        for (x in MAP_SCALE - 1 downTo 0) {
            for (y in 0 until MAP_SCALE) {
                if (y >= x shl 1) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![2]!![1] = shape

        index = 0
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        for (x in 0 until MAP_SCALE) {
            for (y in 0 until MAP_SCALE) {
                if (y <= x shr 1) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![2]!![2] = shape

        index = 0
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        for (x in 0 until MAP_SCALE) {
            for (y in MAP_SCALE - 1 downTo 0) {
                if (y >= x shl 1) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![2]!![3] = shape
    }

    fun genTileShapes4() {
        var index = 0
        var shape = ByteArray(MAP_SCALE * MAP_SCALE)

        for (x in MAP_SCALE - 1 downTo 0) {
            for (y in 0 until MAP_SCALE) {
                if (y >= x shr 1) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![3]!![0] = shape

        index = 0
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        for (x in 0 until MAP_SCALE) {
            for (y in 0 until MAP_SCALE) {
                if (y <= x shl 1) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![3]!![1] = shape

        index = 0
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        for (x in 0 until MAP_SCALE) {
            for (y in MAP_SCALE - 1 downTo 0) {
                if (y >= x shr 1) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![3]!![2] = shape

        index = 0
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        for (x in MAP_SCALE - 1 downTo 0) {
            for (y in MAP_SCALE - 1 downTo 0) {
                if (y <= x shl 1) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![3]!![3] = shape
    }

    fun genTileShapes5() {
        var index = 0
        var shape = ByteArray(MAP_SCALE * MAP_SCALE)

        for (x in MAP_SCALE - 1 downTo 0) {
            for (y in MAP_SCALE - 1 downTo 0) {
                if (y >= x shr 1) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![4]!![0] = shape

        index = 0
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        for (x in MAP_SCALE - 1 downTo 0) {
            for (y in 0 until MAP_SCALE) {
                if (y <= x shl 1) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![4]!![1] = shape

        index = 0
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        for (x in 0 until MAP_SCALE) {
            for (y in 0 until MAP_SCALE) {
                if (y >= x shr 1) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![4]!![2] = shape

        index = 0
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        for (x in 0 until MAP_SCALE) {
            for (y in MAP_SCALE - 1 downTo 0) {
                if (y <= x shl 1) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![4]!![3] = shape
    }

    fun genTileShapes6() {
        var index = 0
        var shape = ByteArray(MAP_SCALE * MAP_SCALE)

        for (x in 0 until MAP_SCALE) {
            for (y in 0 until MAP_SCALE) {
                if (y <= MAP_SCALE / 2) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![5]!![0] = shape

        index = 0
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        for (x in 0 until MAP_SCALE) {
            for (y in 0 until MAP_SCALE) {
                if (x <= MAP_SCALE / 2) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![5]!![1] = shape

        index = 0
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        for (x in 0 until MAP_SCALE) {
            for (y in 0 until MAP_SCALE) {
                if (y >= MAP_SCALE / 2) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![5]!![2] = shape

        index = 0
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        for (x in 0 until MAP_SCALE) {
            for (y in 0 until MAP_SCALE) {
                if (x >= MAP_SCALE / 2) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![5]!![3] = shape
    }

    fun genTileShapes7() {
        var index = 0
        var shape = ByteArray(MAP_SCALE * MAP_SCALE)

        for (x in 0 until MAP_SCALE) {
            for (y in 0 until MAP_SCALE) {
                if (y <= x - MAP_SCALE / 2) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![6]!![0] = shape

        index = 0
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        for (x in MAP_SCALE - 1 downTo 0) {
            for (y in 0 until MAP_SCALE) {
                if (y <= x - MAP_SCALE / 2) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![6]!![1] = shape

        index = 0
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        for (x in MAP_SCALE - 1 downTo 0) {
            for (y in MAP_SCALE - 1 downTo 0) {
                if (y <= x - MAP_SCALE / 2) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![6]!![2] = shape

        index = 0
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        for (x in 0 until MAP_SCALE) {
            for (y in MAP_SCALE - 1 downTo 0) {
                if (y <= x - MAP_SCALE / 2) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![6]!![3] = shape
    }

    fun genTileShapes8() {
        var index = 0
        var shape = ByteArray(MAP_SCALE * MAP_SCALE)

        for (x in 0 until MAP_SCALE) {
            for (y in 0 until MAP_SCALE) {
                if (y >= x - MAP_SCALE / 2) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![7]!![0] = shape

        index = 0
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        for (x in MAP_SCALE - 1 downTo 0) {
            for (y in 0 until MAP_SCALE) {
                if (y >= x - MAP_SCALE / 2) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![7]!![1] = shape

        index = 0
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        for (x in MAP_SCALE - 1 downTo 0) {
            for (y in MAP_SCALE - 1 downTo 0) {
                if (y >= x - MAP_SCALE / 2) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![7]!![2] = shape

        index = 0
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        for (x in 0 until MAP_SCALE) {
            for (y in MAP_SCALE - 1 downTo 0) {
                if (y >= x - MAP_SCALE / 2) {
                    shape[index] = -1
                }
                index++
            }
        }
        TILE_SHAPE_2D!![7]!![3] = shape
    }

    private fun drawObjects(image: BufferedImage, drawBaseX: Int, drawBaseY: Int, region: Region, z: Int) {
        if (!renderObjects) {
            return
        }

        val planeLocs: MutableList<Location?> = ArrayList<Location?>()
        val pushDownLocs: MutableList<Location?> = ArrayList<Location?>()
        val layers = Arrays.asList<MutableList<Location?>?>(planeLocs, pushDownLocs)
        for (localX in 0..<Region.X) {
            val regionX: Int = localX + region.baseX
            for (localY in 0..<Region.Y) {
                val regionY: Int = localY + region.baseY

                planeLocs.clear()
                pushDownLocs.clear()
                val isBridge = (region.getTileSetting(1, localX, localY) and 2).toInt() != 0
                val tileZ = z + (if (isBridge) 1 else 0)

                for (loc in region.locations) {
                    val pos: Position = loc.position
                    if (pos.x !== regionX || pos.y !== regionY) {
                        continue
                    }

                    if (pos.z === tileZ && (region.getTileSetting(z, localX, localY) and 24).toInt() == 0) {
                        planeLocs.add(loc)
                    } else if (z < 3 && pos.z === tileZ + 1 && (region.getTileSetting(
                            z + 1,
                            localX,
                            localY
                        ) and 8).toInt() != 0
                    ) {
                        pushDownLocs.add(loc)
                    }
                }

                for (locs in layers) {
                    if (renderWalls) {
                        for (location in locs) {
                            val type: Int = location!!.type
                            if (type >= 0 && type <= 3) {
                                val rotation: Int = location.orientation

                                val `object`: ObjectType = findObject(location.id)

                                val drawX = (drawBaseX + localX) * MAP_SCALE
                                val drawY: Int = (drawBaseY + (Region.Y - `object`.sizeY - localY)) * MAP_SCALE

                                var rgb = wallColor
                                if (`object`.interactive !== 0) {
                                    rgb = doorColor
                                }
                                rgb = rgb or -0x1000000

                                if (`object`.mapSceneID !== -1) {
                                    blitMapDecoration(image, drawX, drawY, `object`)
                                } else if (drawX >= 0 && drawY >= 0 && drawX < image.getWidth() && drawY < image.getHeight()) {
                                    //Straight walls/doors
                                    if (type == 0 || type == 2) {
                                        var xOffset = if (rotation == 2) MAP_SCALE - 1 else 0
                                        var yOffset = if (rotation == 3) MAP_SCALE - 1 else 0
                                        do {
                                            image.setRGB(drawX + xOffset, drawY + yOffset, rgb)
                                            when (rotation) {
                                                0, 2 -> yOffset++
                                                1, 3 -> xOffset++
                                            }
                                        } while (xOffset < MAP_SCALE && yOffset < MAP_SCALE)
                                    }

                                    //Single dots/pillars
                                    if (type == 3) {
                                        if (rotation == 0) {
                                            image.setRGB(drawX + 0, drawY + 0, rgb)
                                        } else if (rotation == 1) {
                                            image.setRGB(drawX + MAP_SCALE - 1, drawY + 0, rgb)
                                        } else if (rotation == 2) {
                                            image.setRGB(drawX + MAP_SCALE - 1, drawY + MAP_SCALE - 1, rgb)
                                        } else if (rotation == 3) {
                                            image.setRGB(drawX + 0, drawY + MAP_SCALE - 1, rgb)
                                        }
                                    }

                                    //Corners
                                    if (type == 2) {
                                        var xOffset = if (rotation == 1) MAP_SCALE - 1 else 0
                                        var yOffset = if (rotation == 2) MAP_SCALE - 1 else 0
                                        do {
                                            image.setRGB(drawX + xOffset, drawY + yOffset, rgb)
                                            when (rotation) {
                                                1, 3 -> yOffset++
                                                0, 2 -> xOffset++
                                            }
                                        } while (xOffset < MAP_SCALE && yOffset < MAP_SCALE)
                                    }
                                }
                            }
                        }

                        for (location in locs) {
                            val type: Int = location!!.type
                            if (type == 9) {
                                val rotation: Int = location.orientation

                                val `object`: ObjectType = findObject(location.id)

                                val drawX = (drawBaseX + localX) * MAP_SCALE
                                val drawY: Int = (drawBaseY + (Region.Y - `object`.sizeY - localY)) * MAP_SCALE

                                if (`object`.mapSceneID !== -1) {
                                    blitMapDecoration(image, drawX, drawY, `object`)
                                    continue
                                }

                                if (drawX >= 0 && drawY >= 0 && drawX < image.getWidth() && drawY < image.getHeight()) {
                                    var rgb = this.wallColor
                                    if (`object`.interactive !== 0) {
                                        rgb = this.doorColor
                                    }

                                    if (rotation != 0 && rotation != 2) {
                                        var xOffset = 0
                                        var yOffset = 0
                                        do {
                                            image.setRGB(drawX + xOffset, drawY + yOffset, rgb)
                                            yOffset++
                                            xOffset++
                                        } while (xOffset < MAP_SCALE && yOffset < MAP_SCALE)
                                    } else {
                                        var xOffset = 0
                                        var yOffset = MAP_SCALE - 1
                                        do {
                                            image.setRGB(drawX + xOffset, drawY + yOffset, rgb)
                                            yOffset--
                                            xOffset++
                                        } while (xOffset < MAP_SCALE && yOffset >= 0)
                                    }
                                }
                            }
                        }
                    }

                    //Drawing map sprites
                    for (location in locs) {
                        val type: Int = location!!.type
                        if (type == 22 || (type >= 9 && type <= 11)) {
                            val `object`: ObjectType = findObject(location.id)

                            val drawX = (drawBaseX + localX) * MAP_SCALE
                            //What is offsetY?
                            val objSizeOffset = Math.max(2, `object`.offsetY)
                            val drawY = (drawBaseY + (Region.Y - objSizeOffset - localY)) * MAP_SCALE

                            if (`object`.mapSceneID !== -1) {
                                blitMapDecoration(image, drawX, drawY, `object`)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun blitMapDecoration(dst: BufferedImage?, x: Int, y: Int, obj: ObjectType) {
        val sprite: IndexedSprite = spriteTypes[317]!!.sprites[obj.mapSceneID]
        val scale = MAP_SCALE / 4f
        blitIcon(dst!!, x, y + MAP_SCALE, sprite, scale)
    }

    private fun blitIcon(dst: BufferedImage, x: Int, y: Int, sprite: IndexedSprite, scale: Float) {
        val spriteImage = sprite.toBufferedImage()

        val g = dst.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g.drawImage(
            spriteImage,
            x + sprite.offsetX,
            y + sprite.offsetY,
            (spriteImage.width * scale).toInt(),
            (spriteImage.height * scale).toInt(),
            null
        )
        g.dispose()
    }


    private fun drawObjects(image: BufferedImage, z: Int) {
        val lowestXBase = regionLoader.lowestX?.baseX ?: 0
        val highestYBase = regionLoader.highestY?.baseY ?: 0
        
        for (region in regionLoader.regions) {
            val baseX = region.baseX
            val baseY = region.baseY

            // to pixel X
            val drawBaseX = baseX - lowestXBase

            // to pixel Y. top most y is 0, but the top most
            // region has the greatest y, so invert
            val drawBaseY = highestYBase - baseY

            drawObjects(image, drawBaseX, drawBaseY, region, z)
        }
    }

    private fun findObject(id: Int): ObjectType {
        return objectManager[id] ?: error("Object not found: $id")
    }

    private fun packHslFull(hue: Int, saturation: Int, light: Int): Int {
        return JagexColor.packHSLFull(hue, saturation, light)
    }

    private fun drawMapSquare(pixels: Array<IntArray?>, x: Int, y: Int, rgb: Int) {
        var x = x
        var y = y
        x *= MAP_SCALE
        y *= MAP_SCALE

        for (i in 0..<MAP_SCALE) {
            for (j in 0..<MAP_SCALE) {
                pixels[x + i]!![y + j] = rgb
            }
        }
    }

    @Throws(IOException::class)
    private fun loadRegions() {
        regionLoader.loadRegions()
        regionLoader.calculateBounds()
    }

    private fun findUnderlay(id: Int): UnderlayType {
        return underlays[id] ?: error("Underlay not found: $id")
    }

    private fun findOverlay(id: Int): OverlayType {
        return overlays[id] ?: error("Overlay not found: $id")
    }

    private var TILE_SHAPE_2D: Array<Array<ByteArray?>?>? = null


    private var MAP_SCALE = 4

    companion object {

        private const val BLEND = 5


        private fun convert(d: Int): Int = if (d >= 0) {
            d % 64
        } else {
            64 - (-(d % 64)) - 1
        }

        fun adjustHSL_something(hsl: Int, constant: Int): Int {
            //Same as adjustHSLListness0 except this one is missing if hsl == -1 return a clamped constant
            if (hsl == -1) {
                return 12345678
            }
            val adjustedConstant = ((hsl and 127) * constant / 128).coerceIn(2, 126)
            //0xFF80
            return (hsl and 65408) + adjustedConstant
        }

        fun adjustHSLListness0(hsl: Int): Int {
            val multiplier = 0.898
            val hue: Int = JagexColor.unpackHueFull(hsl)
            val saturation: Int = JagexColor.unpackSaturationFull(hsl)
            val light: Int = JagexColor.unpackLuminanceFull(hsl)
            var constant = (light * multiplier).toInt()
            if (constant < 2) {
                constant = 2
            } else if (constant > 255) {
                constant = 255
            }

            return JagexColor.packHSLFull(hue, saturation, constant)
        }
    }
}
