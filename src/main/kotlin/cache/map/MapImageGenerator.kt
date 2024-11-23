/*
 * Copyright (c) 2016-2017, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package cache.map

import mu.KotlinLogging
import net.runelite.cache.*
import net.runelite.cache.definitions.ObjectDefinition
import net.runelite.cache.definitions.OverlayDefinition
import net.runelite.cache.definitions.SpriteDefinition
import net.runelite.cache.definitions.UnderlayDefinition
import net.runelite.cache.definitions.loaders.OverlayLoader
import net.runelite.cache.definitions.loaders.SpriteLoader
import net.runelite.cache.definitions.loaders.UnderlayLoader
import net.runelite.cache.fs.Store
import net.runelite.cache.item.RSTextureProvider
import net.runelite.cache.models.JagexColor
import net.runelite.cache.region.Location
import net.runelite.cache.region.Region
import net.runelite.cache.region.RegionLoader
import net.runelite.cache.util.BigBufferedImage
import net.runelite.cache.util.KeyProvider
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.IOException
import java.util.*
import kotlin.math.max
import kotlin.math.min

private val logger = KotlinLogging.logger {}

class MapImageDumper(private val store: Store, private val regionLoader: RegionLoader) {
    private val wallColor = (238 + (random() * 20.0).toInt() - 10 shl 16) + (238 + (random() * 20.0).toInt() - 10 shl 8) + (238 + (random() * 20.0).toInt() - 10)
    private val doorColor = 238 + (random() * 20.0).toInt() - 10 shl 16

    private val underlays: MutableMap<Int, UnderlayDefinition> = HashMap()
    private val overlays: MutableMap<Int, OverlayDefinition> = HashMap()
    private lateinit var mapDecorations: Array<SpriteDefinition>

    private val areas = AreaManager(store)
    private val sprites = SpriteManager(store)
    private val fonts = FontManager(store)
    private val worldMapManager = WorldMapManager(store)
    private var rsTextureProvider: RSTextureProvider? = null
    private val objectManager = ObjectManager(store)



    private val labelRegions = false



    private val outlineRegions = false



    private val renderMap = true



    private val renderObjects = true



    private val renderIcons = true



    private val renderWalls = true



    private val renderOverlays = true



    private val renderLabels = true



    private val transparency = false



    private val lowMemory = true

    constructor(store: Store, keyProvider: KeyProvider?) : this(store, RegionLoader(store, keyProvider))

    protected fun random(): Double {
        // the client would use a random value here, but we prefer determinism
        return -1.2
    }

    @Throws(IOException::class)
    fun load(): MapImageDumper {
        loadUnderlays(store)
        loadOverlays(store)
        objectManager.load()

        val textureManager = TextureManager(store)
        textureManager.load()
        rsTextureProvider = RSTextureProvider(textureManager, sprites)

        loadRegions()
        areas.load()
        sprites.load()
        loadSprites()
        fonts.load()
        worldMapManager.load()

        return this
    }


    fun drawMap(z: Int): BufferedImage {
        val minX = regionLoader.lowestX.baseX
        val minY = regionLoader.lowestY.baseY

        val maxX = regionLoader.highestX.baseX + Region.X
        val maxY = regionLoader.highestY.baseY + Region.Y

        val dimX = maxX - minX
        val dimY = maxY - minY

        val pixelsX = dimX * MAP_SCALE
        val pixelsY = dimY * MAP_SCALE

        logger.info(
            "Map image dimensions: {}px x {}px, {}px per map square ({} MB). Max memory: {}mb", pixelsX, pixelsY,
            MAP_SCALE, (pixelsX * pixelsY * 3 / 1024 / 1024),
            Runtime.getRuntime().maxMemory() / 1024L / 1024L
        )
        val image = if (lowMemory) {
            BigBufferedImage.create(
                pixelsX,
                pixelsY,
                if (transparency) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
            )
        } else {
            BufferedImage(
                pixelsX,
                pixelsY,
                if (transparency) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
            )
        }

        drawMap(image, z)
        drawObjects(image, z)
        drawMapIcons(image, z)
        drawMapLabels(image, z)

        return image
    }

    private fun drawNeighborObjects(image: BufferedImage, rx: Int, ry: Int, dx: Int, dy: Int, z: Int) {
        val neighbor = regionLoader.findRegionForRegionCoordinates(rx + dx, ry + dy) ?: return

        drawObjects(image, Region.X * dx, Region.Y * -dy, neighbor, z)
    }

    fun drawRegion(region: Region, z: Int): BufferedImage {
        val pixelsX = Region.X * MAP_SCALE
        val pixelsY = Region.Y * MAP_SCALE

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
        drawMapIcons(image, 0, 0, region, z)

        return image
    }

    private fun drawMap(image: BufferedImage, drawBaseX: Int, drawBaseY: Int, z: Int, region: Region) {
        if (!renderMap) {
            return
        }

        val map: Array<Array<IntArray>?> = arrayOfNulls(4)

        for (x in 0 until Region.X) {
            for (y in 0 until Region.Y) {
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
        for (region in regionLoader.regions) {
            val baseX = region.baseX
            val baseY = region.baseY

            // to pixel X
            val drawBaseX = baseX - regionLoader.lowestX.baseX

            // to pixel Y. top most y is 0, but the top most
            // region has the greatest y, so invert
            val drawBaseY = regionLoader.highestY.baseY - baseY

            drawMap(image, drawBaseX, drawBaseY, z, region)
        }
    }

    private fun drawTile(
        to: BufferedImage,
        planes: Array<Array<IntArray>?>,
        region: Region,
        drawBaseX: Int,
        drawBaseY: Int,
        z: Int,
        x: Int,
        y: Int
    ) {
        var pixels = planes[z]

        if (pixels == null) {
            planes[z] = Array(Region.X * MAP_SCALE) { IntArray(Region.Y * MAP_SCALE) }
            pixels = planes[z]
            drawMap(pixels, region, z)
        }

        for (i in 0 until MAP_SCALE) {
            for (j in 0 until MAP_SCALE) {
                val argb = pixels?.get(x * MAP_SCALE + i)?.get(y * MAP_SCALE + j)
                if (argb != 0) {
                    if (argb != null) {
                        to.setRGB(
                            drawBaseX * MAP_SCALE + x * MAP_SCALE + i,
                            drawBaseY * MAP_SCALE + y * MAP_SCALE + j,
                            argb
                        )
                    }
                }
            }
        }
    }

    private fun drawMap(pixels: Array<IntArray>?, region: Region, z: Int) {
        if (TILE_SHAPE_2D == null) {
            generateTileShapes()
        }

        val baseX = region.baseX
        val baseY = region.baseY

        val len = Region.X + BLEND * 2
        val hues = IntArray(len)
        val sats = IntArray(len)
        val light = IntArray(len)
        val mul = IntArray(len)
        val num = IntArray(len)

        val hasLeftRegion = regionLoader.findRegionForWorldCoordinates(baseX - 1, baseY) != null
        val hasRightRegion = regionLoader.findRegionForWorldCoordinates(baseX + Region.X, baseY) != null
        val hasUpRegion = regionLoader.findRegionForWorldCoordinates(baseX, baseY + Region.Y) != null
        val hasDownRegion = regionLoader.findRegionForWorldCoordinates(baseX, baseY - 1) != null

        for (xi in (if (hasLeftRegion) -BLEND * 2 else -BLEND) until (Region.X + (if (hasRightRegion) BLEND * 2 else BLEND))) {
            for (yi in (if (hasDownRegion) -BLEND else 0) until (Region.Y + (if (hasUpRegion) BLEND else 0))) {
                val xr = xi + BLEND
                if (xr >= (if (hasLeftRegion) -BLEND else 0) && xr < Region.X + (if (hasRightRegion) BLEND else 0)) {
                    val r = regionLoader.findRegionForWorldCoordinates(baseX + xr, baseY + yi)
                    if (r != null) {
                        val underlayId = r.getUnderlayId(z, convert(xr), convert(yi))
                        if (underlayId > 0) {
                            val underlay = findUnderlay(underlayId - 1)
                            hues[yi + BLEND] += underlay!!.hue * 256 / underlay.hueMultiplier
                            sats[yi + BLEND] += underlay.saturation
                            light[yi + BLEND] += underlay.lightness
                            mul[yi + BLEND] += underlay.hueMultiplier
                            num[yi + BLEND]++
                        }
                    }
                }

                val xl = xi - BLEND
                if (xl >= (if (hasLeftRegion) -BLEND else 0) && xl < Region.X + (if (hasRightRegion) BLEND else 0)) {
                    val r = regionLoader.findRegionForWorldCoordinates(baseX + xl, baseY + yi)
                    if (r != null) {
                        val underlayId = r.getUnderlayId(z, convert(xl), convert(yi))
                        if (underlayId > 0) {
                            val underlay = findUnderlay(underlayId - 1)
                            hues[yi + BLEND] -= underlay!!.hue * 256 / underlay.hueMultiplier
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

                for (yi in (if (hasDownRegion) -BLEND * 2 else -BLEND) until (Region.Y + (if (hasUpRegion) BLEND * 2 else BLEND))) {
                    val yu = yi + BLEND
                    if (yu >= (if (hasDownRegion) -BLEND else 0) && yu < Region.Y + (if (hasUpRegion) BLEND else 0)) {
                        runningHues += hues[yu + BLEND]
                        runningSat += sats[yu + BLEND]
                        runningLight += light[yu + BLEND]
                        runningMultiplier += mul[yu + BLEND]
                        runningNumber += num[yu + BLEND]
                    }

                    val yd = yi - BLEND
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
                                    val overlayTexture = overlayDefinition!!.texture
                                    var hsl: Int

                                    if (overlayTexture >= 0) {
                                        hsl = rsTextureProvider!!.getAverageTextureRGB(overlayTexture)
                                        //Repack it into full hsl
                                        hsl = packHslFull(
                                            JagexColor.unpackHue(hsl.toShort()) * 4,
                                            JagexColor.unpackSaturation(hsl.toShort()) * 32,
                                            JagexColor.unpackLuminance(hsl.toShort()) * 2
                                        )
                                    } else if (overlayDefinition.rgbColor == 0xFF00FF) {
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
                                    if (overlayDefinition.secondaryRgbColor != -1) {
                                        val hue = overlayDefinition.otherHue
                                        val sat = overlayDefinition.otherSaturation
                                        val olight = overlayDefinition.otherLightness
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
                                    val drawX = xi * MAP_SCALE
                                    val drawY = (Region.Y - 1 - yi) * MAP_SCALE
                                    rotation = convertTileRotation(rotation, shape)
                                    shape = convertTileShape(shape)
                                    if (underlayRgb != 0) {
                                        //blending between ground types
                                        var idx = 0
                                        for (iy in 0 until MAP_SCALE) {
                                            for (ix in 0 until MAP_SCALE) {
                                                val p =
                                                    if (TILE_SHAPE_2D!![shape - 1][rotation]!![idx++].toInt() == 0) underlayRgb else overlayRgb
                                                setPixels(pixels, drawX + ix, drawY + iy, p)
                                            }
                                        }
                                    } else {
                                        //Only draw the overlay portion of the tile. Used extensively for the coastlines of tutorial island.
                                        var rotIdx = 0
                                        for (iy in 0 until MAP_SCALE) {
                                            for (ix in 0 until MAP_SCALE) {
                                                if (TILE_SHAPE_2D!![shape - 1][rotation]!![rotIdx++].toInt() != 0) {
                                                    setPixels(pixels, drawX + ix, drawY + iy, overlayRgb)
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

    private fun setPixels(pixels: Array<IntArray>?, x: Int, y: Int, value: Int) {
        if (x >= 0 && y >= 0 && x < pixels!!.size && y < pixels[x].size) {
            pixels[x][y] = value
        } else {
            System.err.println("Ground drawing out of bounds! $x, $y")
        }
    }

    private fun convertTileRotation(rotation: Int, shape: Int): Int {
        var rotation = rotation
        if (shape == 9) {
            rotation = rotation + 1 and 3
        }

        if (shape == 10) {
            rotation = rotation + 3 and 3
        }

        if (shape == 11) {
            rotation = rotation + 3 and 3
        }

        return rotation
    }

    fun convertTileShape(shape: Int): Int {
        return if (shape != 9 && shape != 10) (if (shape == 11) 8 else shape) else 1
    }

    private fun generateTileShapes() {
        TILE_SHAPE_2D = Array(8) { arrayOfNulls(4) }
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
        var shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var index = 0
        var y: Int
        var x = 0
        while (x < MAP_SCALE) {
            y = 0
            while (y < MAP_SCALE) {
                if (y <= x) {
                    shape[index] = -1
                }

                ++index
                ++y
            }
            ++x
        }

        TILE_SHAPE_2D!![0][0] = shape
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        index = 0

        x = MAP_SCALE - 1
        while (x >= 0) {
            y = 0
            while (y < MAP_SCALE) {
                if (y <= x) {
                    shape[index] = -1
                }

                ++index
                ++y
            }
            --x
        }

        TILE_SHAPE_2D!![0][1] = shape
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        index = 0

        x = 0
        while (x < MAP_SCALE) {
            y = 0
            while (y < MAP_SCALE) {
                if (y >= x) {
                    shape[index] = -1
                }

                ++index
                ++y
            }
            ++x
        }

        TILE_SHAPE_2D!![0][2] = shape
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        index = 0

        x = MAP_SCALE - 1
        while (x >= 0) {
            y = 0
            while (y < MAP_SCALE) {
                if (y >= x) {
                    shape[index] = -1
                }

                ++index
                ++y
            }
            --x
        }

        TILE_SHAPE_2D!![0][3] = shape
    }

    fun genTileShapes2() {
        var shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var var2 = 0
        var var4: Int
        var var3 = MAP_SCALE - 1
        while (var3 >= 0) {
            var4 = 0
            while (var4 < MAP_SCALE) {
                if (var4 <= var3 shr 1) {
                    shape[var2] = -1
                }

                ++var2
                ++var4
            }
            --var3
        }

        TILE_SHAPE_2D!![1][0] = shape
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var2 = 0

        var3 = 0
        while (var3 < MAP_SCALE) {
            var4 = 0
            while (var4 < MAP_SCALE) {
                if (var2 >= 0 && var2 < shape.size) {
                    if (var4 >= var3 shl 1) {
                        shape[var2] = -1
                    }

                    ++var2
                } else {
                    ++var2
                }
                ++var4
            }
            ++var3
        }

        TILE_SHAPE_2D!![1][1] = shape
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var2 = 0

        var3 = 0
        while (var3 < MAP_SCALE) {
            var4 = MAP_SCALE - 1
            while (var4 >= 0) {
                if (var4 <= var3 shr 1) {
                    shape[var2] = -1
                }

                ++var2
                --var4
            }
            ++var3
        }

        TILE_SHAPE_2D!![1][2] = shape
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var2 = 0

        var3 = MAP_SCALE - 1
        while (var3 >= 0) {
            var4 = MAP_SCALE - 1
            while (var4 >= 0) {
                if (var4 >= var3 shl 1) {
                    shape[var2] = -1
                }

                ++var2
                --var4
            }
            --var3
        }

        TILE_SHAPE_2D!![1][3] = shape
    }

    fun genTileShapes3() {
        var shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var var2 = 0
        var var4: Int
        var var3 = MAP_SCALE - 1
        while (var3 >= 0) {
            var4 = MAP_SCALE - 1
            while (var4 >= 0) {
                if (var4 <= var3 shr 1) {
                    shape[var2] = -1
                }

                ++var2
                --var4
            }
            --var3
        }

        TILE_SHAPE_2D!![2][0] = shape
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var2 = 0

        var3 = MAP_SCALE - 1
        while (var3 >= 0) {
            var4 = 0
            while (var4 < MAP_SCALE) {
                if (var4 >= var3 shl 1) {
                    shape[var2] = -1
                }

                ++var2
                ++var4
            }
            --var3
        }

        TILE_SHAPE_2D!![2][1] = shape
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var2 = 0

        var3 = 0
        while (var3 < MAP_SCALE) {
            var4 = 0
            while (var4 < MAP_SCALE) {
                if (var4 <= var3 shr 1) {
                    shape[var2] = -1
                }

                ++var2
                ++var4
            }
            ++var3
        }

        TILE_SHAPE_2D!![2][2] = shape
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var2 = 0

        var3 = 0
        while (var3 < MAP_SCALE) {
            var4 = MAP_SCALE - 1
            while (var4 >= 0) {
                if (var4 >= var3 shl 1) {
                    shape[var2] = -1
                }

                ++var2
                --var4
            }
            ++var3
        }

        TILE_SHAPE_2D!![2][3] = shape
    }

    fun genTileShapes4() {
        var shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var var2 = 0
        var var4: Int
        var var3 = MAP_SCALE - 1
        while (var3 >= 0) {
            var4 = 0
            while (var4 < MAP_SCALE) {
                if (var4 >= var3 shr 1) {
                    shape[var2] = -1
                }

                ++var2
                ++var4
            }
            --var3
        }

        TILE_SHAPE_2D!![3][0] = shape
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var2 = 0

        var3 = 0
        while (var3 < MAP_SCALE) {
            var4 = 0
            while (var4 < MAP_SCALE) {
                if (var4 <= var3 shl 1) {
                    shape[var2] = -1
                }

                ++var2
                ++var4
            }
            ++var3
        }

        TILE_SHAPE_2D!![3][1] = shape
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var2 = 0

        var3 = 0
        while (var3 < MAP_SCALE) {
            var4 = MAP_SCALE - 1
            while (var4 >= 0) {
                if (var4 >= var3 shr 1) {
                    shape[var2] = -1
                }

                ++var2
                --var4
            }
            ++var3
        }

        TILE_SHAPE_2D!![3][2] = shape
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var2 = 0

        var3 = MAP_SCALE - 1
        while (var3 >= 0) {
            var4 = MAP_SCALE - 1
            while (var4 >= 0) {
                if (var4 <= var3 shl 1) {
                    shape[var2] = -1
                }

                ++var2
                --var4
            }
            --var3
        }

        TILE_SHAPE_2D!![3][3] = shape
    }

    fun genTileShapes5() {
        var shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var var2 = 0
        var var4: Int
        var var3 = MAP_SCALE - 1
        while (var3 >= 0) {
            var4 = MAP_SCALE - 1
            while (var4 >= 0) {
                if (var4 >= var3 shr 1) {
                    shape[var2] = -1
                }

                ++var2
                --var4
            }
            --var3
        }

        TILE_SHAPE_2D!![4][0] = shape
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var2 = 0

        var3 = MAP_SCALE - 1
        while (var3 >= 0) {
            var4 = 0
            while (var4 < MAP_SCALE) {
                if (var4 <= var3 shl 1) {
                    shape[var2] = -1
                }

                ++var2
                ++var4
            }
            --var3
        }

        TILE_SHAPE_2D!![4][1] = shape
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var2 = 0

        var3 = 0
        while (var3 < MAP_SCALE) {
            var4 = 0
            while (var4 < MAP_SCALE) {
                if (var4 >= var3 shr 1) {
                    shape[var2] = -1
                }

                ++var2
                ++var4
            }
            ++var3
        }

        TILE_SHAPE_2D!![4][2] = shape
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var2 = 0

        var3 = 0
        while (var3 < MAP_SCALE) {
            var4 = MAP_SCALE - 1
            while (var4 >= 0) {
                if (var4 <= var3 shl 1) {
                    shape[var2] = -1
                }

                ++var2
                --var4
            }
            ++var3
        }

        TILE_SHAPE_2D!![4][3] = shape
    }

    fun genTileShapes6() {
        var shape = ByteArray(MAP_SCALE * MAP_SCALE)
        val var2 = false
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var var3 = 0
        var var5: Int
        var var4 = 0
        while (var4 < MAP_SCALE) {
            var5 = 0
            while (var5 < MAP_SCALE) {
                if (var5 <= MAP_SCALE / 2) {
                    shape[var3] = -1
                }

                ++var3
                ++var5
            }
            ++var4
        }

        TILE_SHAPE_2D!![5][0] = shape
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var3 = 0

        var4 = 0
        while (var4 < MAP_SCALE) {
            var5 = 0
            while (var5 < MAP_SCALE) {
                if (var4 <= MAP_SCALE / 2) {
                    shape[var3] = -1
                }

                ++var3
                ++var5
            }
            ++var4
        }

        TILE_SHAPE_2D!![5][1] = shape
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var3 = 0

        var4 = 0
        while (var4 < MAP_SCALE) {
            var5 = 0
            while (var5 < MAP_SCALE) {
                if (var5 >= MAP_SCALE / 2) {
                    shape[var3] = -1
                }

                ++var3
                ++var5
            }
            ++var4
        }

        TILE_SHAPE_2D!![5][2] = shape
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var3 = 0

        var4 = 0
        while (var4 < MAP_SCALE) {
            var5 = 0
            while (var5 < MAP_SCALE) {
                if (var4 >= MAP_SCALE / 2) {
                    shape[var3] = -1
                }

                ++var3
                ++var5
            }
            ++var4
        }

        TILE_SHAPE_2D!![5][3] = shape
    }

    fun genTileShapes7() {
        var shape = ByteArray(MAP_SCALE * MAP_SCALE)
        val var2 = false
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var var3 = 0
        var var5: Int
        var var4 = 0
        while (var4 < MAP_SCALE) {
            var5 = 0
            while (var5 < MAP_SCALE) {
                if (var5 <= var4 - MAP_SCALE / 2) {
                    shape[var3] = -1
                }

                ++var3
                ++var5
            }
            ++var4
        }

        TILE_SHAPE_2D!![6][0] = shape
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var3 = 0

        var4 = MAP_SCALE - 1
        while (var4 >= 0) {
            var5 = 0
            while (var5 < MAP_SCALE) {
                if (var5 <= var4 - MAP_SCALE / 2) {
                    shape[var3] = -1
                }

                ++var3
                ++var5
            }
            --var4
        }

        TILE_SHAPE_2D!![6][1] = shape
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var3 = 0

        var4 = MAP_SCALE - 1
        while (var4 >= 0) {
            var5 = MAP_SCALE - 1
            while (var5 >= 0) {
                if (var5 <= var4 - MAP_SCALE / 2) {
                    shape[var3] = -1
                }

                ++var3
                --var5
            }
            --var4
        }

        TILE_SHAPE_2D!![6][2] = shape
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var3 = 0

        var4 = 0
        while (var4 < MAP_SCALE) {
            var5 = MAP_SCALE - 1
            while (var5 >= 0) {
                if (var5 <= var4 - MAP_SCALE / 2) {
                    shape[var3] = -1
                }

                ++var3
                --var5
            }
            ++var4
        }

        TILE_SHAPE_2D!![6][3] = shape
    }

    fun genTileShapes8() {
        var shape = ByteArray(MAP_SCALE * MAP_SCALE)
        val var2 = false
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var var3 = 0
        var var5: Int
        var var4 = 0
        while (var4 < MAP_SCALE) {
            var5 = 0
            while (var5 < MAP_SCALE) {
                if (var5 >= var4 - MAP_SCALE / 2) {
                    shape[var3] = -1
                }

                ++var3
                ++var5
            }
            ++var4
        }

        TILE_SHAPE_2D!![7][0] = shape
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var3 = 0

        var4 = MAP_SCALE - 1
        while (var4 >= 0) {
            var5 = 0
            while (var5 < MAP_SCALE) {
                if (var5 >= var4 - MAP_SCALE / 2) {
                    shape[var3] = -1
                }

                ++var3
                ++var5
            }
            --var4
        }

        TILE_SHAPE_2D!![7][1] = shape
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var3 = 0

        var4 = MAP_SCALE - 1
        while (var4 >= 0) {
            var5 = MAP_SCALE - 1
            while (var5 >= 0) {
                if (var5 >= var4 - MAP_SCALE / 2) {
                    shape[var3] = -1
                }

                ++var3
                --var5
            }
            --var4
        }

        TILE_SHAPE_2D!![7][2] = shape
        shape = ByteArray(MAP_SCALE * MAP_SCALE)
        var3 = 0

        var4 = 0
        while (var4 < MAP_SCALE) {
            var5 = MAP_SCALE - 1
            while (var5 >= 0) {
                if (var5 >= var4 - MAP_SCALE / 2) {
                    shape[var3] = -1
                }

                ++var3
                --var5
            }
            ++var4
        }

        TILE_SHAPE_2D!![7][3] = shape
    }

    private fun drawObjects(image: BufferedImage, drawBaseX: Int, drawBaseY: Int, region: Region, z: Int) {


        val planeLocs: MutableList<Location> = ArrayList()
        val pushDownLocs: MutableList<Location> = ArrayList()
        val layers = Arrays.asList<List<Location>>(planeLocs, pushDownLocs)
        for (localX in 0 until Region.X) {
            val regionX = localX + region.baseX
            for (localY in 0 until Region.Y) {
                val regionY = localY + region.baseY

                planeLocs.clear()
                pushDownLocs.clear()
                val isBridge = (region.getTileSetting(1, localX, localY).toInt() and 2) != 0
                val tileZ = z + (if (isBridge) 1 else 0)

                for (loc in region.locations) {
                    val pos = loc.position
                    if (pos.x != regionX || pos.y != regionY) {
                        continue
                    }

                    if (pos.z == tileZ && (region.getTileSetting(z, localX, localY).toInt() and 24) == 0) {
                        planeLocs.add(loc)
                    } else if (z < 3 && pos.z == tileZ + 1 && (region.getTileSetting(z + 1, localX, localY)
                            .toInt() and 8) != 0
                    ) {
                        pushDownLocs.add(loc)
                    }
                }

                for (locs in layers) {
                    if (renderWalls) {
                        for (location in locs) {
                            val type = location.type
                            if (type >= 0 && type <= 3) {
                                val rotation = location.orientation

                                val `object` = findObject(location.id)

                                val drawX = (drawBaseX + localX) * MAP_SCALE
                                val drawY = (drawBaseY + (Region.Y - `object`.sizeY - localY)) * MAP_SCALE

                                var rgb = wallColor
                                if (`object`.wallOrDoor != 0) {
                                    rgb = doorColor
                                }
                                rgb = rgb or -0x1000000

                                if (`object`.mapSceneID != -1) {
                                    blitMapDecoration(image, drawX, drawY, `object`)
                                } else if (drawX >= 0 && drawY >= 0 && drawX < image.width && drawY < image.height) {
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
                            val type = location.type
                            if (type == 9) {
                                val rotation = location.orientation

                                val `object` = findObject(location.id)


                                val drawX = (drawBaseX + localX) * MAP_SCALE
                                val drawY = (drawBaseY + (Region.Y - `object`.sizeY - localY)) * MAP_SCALE

                                if (`object`.mapSceneID != -1) {
                                    blitMapDecoration(image, drawX, drawY, `object`)
                                    continue
                                }

                                if (drawX >= 0 && drawY >= 0 && drawX < image.width && drawY < image.height) {
                                    var rgb = this.wallColor
                                    if (`object`.wallOrDoor != 0) {
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
                        val type = location.type
                        if (type == 22 || (type >= 9 && type <= 11)) {
                            val `object` = findObject(location.id)

                            val drawX = (drawBaseX + localX) * MAP_SCALE
                            //What is offsetY?
                            val objSizeOffset = max(2.0, `object`.offsetY.toDouble()).toInt()
                            val drawY = (drawBaseY + (Region.Y - objSizeOffset - localY)) * MAP_SCALE

                            if (`object`.mapSceneID != -1) {
                                blitMapDecoration(image, drawX, drawY, `object`)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun drawObjects(image: BufferedImage, z: Int) {
        for (region in regionLoader.regions) {
            val baseX = region.baseX
            val baseY = region.baseY

            // to pixel X
            val drawBaseX = baseX - regionLoader.lowestX.baseX

            // to pixel Y. top most y is 0, but the top most
            // region has the greatest y, so invert
            val drawBaseY = regionLoader.highestY.baseY - baseY

            drawObjects(image, drawBaseX, drawBaseY, region, z)
        }
    }

    private fun drawMapIcons(image: BufferedImage, drawBaseX: Int, drawBaseY: Int, region: Region, z: Int) {
        val baseX = region.baseX
        val baseY = region.baseY

        val graphics = image.createGraphics()

        drawMapIcons(image, region, z, drawBaseX, drawBaseY)

        if (labelRegions) {
            graphics.color = Color.WHITE
            val str = baseX.toString() + "," + baseY + " (" + region.regionX + "," + region.regionY + ")"
            graphics.drawString(str, drawBaseX * MAP_SCALE, drawBaseY * MAP_SCALE + graphics.fontMetrics.height)
        }

        if (outlineRegions) {
            graphics.color = Color.WHITE
            graphics.drawRect(drawBaseX * MAP_SCALE, drawBaseY * MAP_SCALE, Region.X * MAP_SCALE, Region.Y * MAP_SCALE)
        }

        graphics.dispose()
    }

    private fun drawMapIcons(image: BufferedImage, z: Int) {
        // map icons
        for (region in regionLoader.regions) {
            val baseX = region.baseX
            val baseY = region.baseY

            // to pixel X
            val drawBaseX = baseX - regionLoader.lowestX.baseX

            // to pixel Y. top most y is 0, but the top most
            // region has the greatest y, so invert
            val drawBaseY = regionLoader.highestY.baseY - baseY

            drawMapIcons(image, drawBaseX, drawBaseY, region, z)
        }
    }

    private fun drawMapLabels(image: BufferedImage, z: Int) {
        if (!renderLabels) {
            return
        }

        val fontSizes = arrayOf(FontName.VERDANA_11, FontName.VERDANA_13, FontName.VERDANA_15)
        val elements = worldMapManager.elements
        for (element in elements) {
            val area = areas.getArea(element.getAreaDefinitionId())
            val worldPosition = element.worldPosition
            if (area?.getName() == null || worldPosition.z != z) {
                continue
            }

            val fontSize = fontSizes[area.getTextScale()]
            val font = fonts.findFontByName(fontSize.getName())
            val areaLabel = area.getName()
            val lines = areaLabel.split("<br>".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            var ascent = 0

            for (line in lines) {
                var advance = 0
                val stringWidth = font.stringWidth(line)
                for (i in 0 until line.length) {
                    val c = line[i]
                    val sprite = sprites.findSpriteByArchiveName(fontSize.getName(), c.code)
                    if (sprite.width != 0 && sprite.height != 0) {
                        val drawX = worldPosition.x - regionLoader.lowestX.baseX
                        val drawY = regionLoader.highestY.baseY - worldPosition.y + Region.Y - 2
                        blitGlyph(
                            image,
                            (drawX * MAP_SCALE) + advance - (stringWidth / 2),
                            (drawY * MAP_SCALE) + ascent - (font.getAscent() / 2),
                            area.getTextColor(),
                            sprite
                        )
                    }

                    advance += font.getAdvances()[c.code]
                }
                ascent += font.getAscent() / 2
            }
        }
    }

    private fun findObject(id: Int): ObjectDefinition {
        return objectManager.getObject(id)
    }

    private fun packHslFull(hue: Int, saturation: Int, light: Int): Int {
        return JagexColor.packHSLFull(hue, saturation, light)
    }

    private fun drawMapSquare(pixels: Array<IntArray>?, x: Int, y: Int, rgb: Int) {
        var x = x
        var y = y
        x *= MAP_SCALE
        y *= MAP_SCALE

        for (i in 0 until MAP_SCALE) {
            for (j in 0 until MAP_SCALE) {
                pixels!![x + i][y + j] = rgb
            }
        }
    }

    private fun drawMapIcons(img: BufferedImage, region: Region, z: Int, drawBaseX: Int, drawBaseY: Int) {
        if (!renderIcons) {
            return
        }

        for (location in region.locations) {
            val localX = location.position.x - region.baseX
            val localY = location.position.y - region.baseY

            if (z != location.position.z) {
                // draw all icons on z=0
                continue
            }

            val od = checkNotNull(findObject(location.id))

            val drawX = drawBaseX + localX
            val drawY = drawBaseY + (Region.Y - 1 - localY)

            if (od.mapAreaId != -1) {
                val area = checkNotNull(areas.getArea(od.mapAreaId))
                val sprite = checkNotNull(sprites.findSprite(area.spriteId, 0))
                blitIcon(
                    img,
                    (drawX * MAP_SCALE) - (sprite.maxWidth / 2),
                    (drawY * MAP_SCALE) - (sprite.maxHeight / 2),
                    sprite, 1.0f
                )
            }
        }

        // Draw the intermap link icons which are not stored with the map locations
        val elements = worldMapManager.elements
        for (element in elements) {
            val area = areas.getArea(element.getAreaDefinitionId())
            val worldPosition = element.worldPosition
            val regionX = worldPosition.x / Region.X
            val regionY = worldPosition.y / Region.Y

            if (area == null || area.getName() != null || worldPosition.z != z || regionX != region.regionX || regionY != region.regionY) {
                continue
            }

            val localX = worldPosition.x - region.baseX
            val localY = worldPosition.y - region.baseY
            val drawX = drawBaseX + localX
            val drawY = drawBaseY + (Region.Y - 1 - localY)
            val sprite = sprites.findSprite(area.spriteId, 0)
            blitIcon(
                img,
                (drawX * MAP_SCALE) - (sprite.maxWidth / 2),
                (drawY * MAP_SCALE) - (sprite.maxHeight / 2),
                sprite, 1.0f
            )
        }
    }

    @Throws(IOException::class)
    private fun loadRegions() {
        regionLoader.loadRegions()
        regionLoader.calculateBounds()

        logger.debug("North most region: {}", regionLoader.lowestY.baseY)
        logger.debug("South most region: {}", regionLoader.highestY.baseY)
        logger.debug("West most region:  {}", regionLoader.lowestX.baseX)
        logger.debug("East most region:  {}", regionLoader.highestX.baseX)
    }

    @Throws(IOException::class)
    private fun loadUnderlays(store: Store) {
        val storage = store.storage
        val index = store.getIndex(IndexType.CONFIGS)
        val archive = index.getArchive(ConfigType.UNDERLAY.id)

        val archiveData = storage.loadArchive(archive)
        val files = archive.getFiles(archiveData)

        for (file in files.files) {
            val loader = UnderlayLoader()
            val underlay = loader.load(file.fileId, file.contents)

            underlays[underlay.id] = underlay
        }
    }

    private fun findUnderlay(id: Int): UnderlayDefinition? {
        return underlays[id]
    }

    @Throws(IOException::class)
    private fun loadOverlays(store: Store) {
        val storage = store.storage
        val index = store.getIndex(IndexType.CONFIGS)
        val archive = index.getArchive(ConfigType.OVERLAY.id)

        val archiveData = storage.loadArchive(archive)
        val files = archive.getFiles(archiveData)

        for (file in files.files) {
            val loader = OverlayLoader()
            val overlay = loader.load(file.fileId, file.contents)

            overlays[overlay.id] = overlay
        }
    }

    private fun findOverlay(id: Int): OverlayDefinition? {
        return overlays[id]
    }

    @Throws(IOException::class)
    private fun loadSprites() {
        val storage = store.storage
        val index = store.getIndex(IndexType.SPRITES)
        val a = index.findArchiveByName("mapscene")
        val contents = a.decompress(storage.loadArchive(a))

        val loader = SpriteLoader()
        mapDecorations = loader.load(a.archiveId, contents)
    }

    private fun blitMapDecoration(dst: BufferedImage, x: Int, y: Int, `object`: ObjectDefinition) {
        val sprite = mapDecorations[`object`.mapSceneID]
        val scale = 1F
        blitIcon(dst, x, y + MAP_SCALE, sprite, scale)
    }

    private fun blitIcon(dst: BufferedImage, x: Int, y: Int, sprite: SpriteDefinition, scale: Float) {
        var x = x
        var y = y
        sprite.normalize() //Sprites are required to be normalized to have small sprites draw correctly
        x += sprite.offsetX
        y += sprite.offsetY
        val displayHeight = (sprite.height * scale).toInt()
        val displayWidth = (sprite.width * scale).toInt()
        val stepSizeHeight = 1 + 1 - scale
        val stepSizeWidth = 1 + 1 - scale

        val ymin = max(0.0, -y.toDouble()).toInt()
        val ymax = min(displayHeight.toDouble(), (dst.height - y).toDouble()).toInt()

        val xmin = max(0.0, -x.toDouble()).toInt()
        val xmax = min(displayWidth.toDouble(), (dst.width - x).toDouble()).toInt()

        var indexX = 0f
        var indexY = 0f
        for (yo in ymin until ymax) {
            for (xo in xmin until xmax) {
                val index =
                    indexX.toInt() + (indexY.toInt() * (sprite.width))
                val color = sprite.pixelIdx[index]
                if (color.toInt() != 0) {
                    dst.setRGB(x + xo, y + yo, sprite.palette[color.toInt() and 255] or -0x1000000)
                }
                indexX += stepSizeWidth
            }
            indexY += stepSizeHeight
            indexX = 0f
        }
    }

    /**
     * Glyph SpriteDefinitions do not have the palette information that blitIcon uses.
     */
    private fun blitGlyphIcon(dst: BufferedImage, x: Int, y: Int, sprite: SpriteDefinition) {
        var x = x
        var y = y
        x += sprite.offsetX
        y += sprite.offsetY

        val ymin = max(0.0, -y.toDouble()).toInt()
        val ymax = min(sprite.height.toDouble(), (dst.height - y).toDouble()).toInt()

        val xmin = max(0.0, -x.toDouble()).toInt()
        val xmax = min(sprite.width.toDouble(), (dst.width - x).toDouble()).toInt()

        for (yo in ymin until ymax) {
            for (xo in xmin until xmax) {
                val rgb = sprite.pixels[xo + (yo * sprite.width)]
                if (rgb != 0) {
                    dst.setRGB(x + xo, y + yo, rgb or -0x1000000)
                }
            }
        }
    }

    private fun blitGlyph(dst: BufferedImage, x: Int, y: Int, color: Int, glyph: SpriteDefinition) {
        val pixels = glyph.pixels
        val shadowPixels = IntArray(pixels.size)
        for (i in pixels.indices) {
            if (pixels[i] != 0) {
                pixels[i] = color
                shadowPixels[i] = -0x1000000
            }
        }
        val shadow = SpriteDefinition()
        shadow.pixels = shadowPixels
        shadow.offsetX = glyph.offsetX
        shadow.offsetY = glyph.offsetY
        shadow.width = glyph.width
        shadow.height = glyph.height

        blitGlyphIcon(dst, x + 1, y + 1, shadow)
        blitGlyphIcon(dst, x, y, glyph)
    }

    companion object {
        var MAP_SCALE = 16 // this squared is the number of pixels per map square
        private const val BLEND = 5 // number of surrounding tiles for ground blending

        var TILE_SHAPE_2D: Array<Array<ByteArray?>>? = null

        private fun convert(d: Int): Int {
            return if (d >= 0) {
                d % 64
            } else {
                64 - -(d % 64) - 1
            }
        }

        fun adjustHSL_something(hsl: Int, constant: Int): Int {
            //Same as adjustHSLListness0 except this one is missing if hsl == -1 return a clamped constant
            var constant = constant
            if (hsl == -1) {
                return 12345678
            } else {
                constant = (hsl and 127) * constant / 128
                if (constant < 2) {
                    constant = 2
                } else if (constant > 126) {
                    constant = 126
                }

                //0xFF80
                return (hsl and 65408) + constant
            }
        }

        fun adjustHSLListness0(hsl: Int): Int {
            val multiplier = 0.898
            val hue = JagexColor.unpackHueFull(hsl)
            val saturation = JagexColor.unpackSaturationFull(hsl)
            val light = JagexColor.unpackLuminanceFull(hsl)
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