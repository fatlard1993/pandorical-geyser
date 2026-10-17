// Draws every block look pandorical-geyser exported beside the Java model it was made from, in
// Blockbench, and reports where the two differ.
//
//   node check.cjs <export dir> <minecraft client jar> [out dir] [only]
//
// [only], when given, keeps the states whose name contains it.
// The export comes from a server started with -Dpandorical-geyser.export=true. The client jar holds
// the vanilla textures a server doesn't have. Exit status is the number of looks that differ.
//
// The Java side is the model as Java has it, each blockstate piece turned by Blockbench's own
// rigid quarter turn and, under uvlock, given the UVs Java's uvlock gives, worked out here: never by
// the mod's code under test, so a mistake there shows up rather than being copied into the reference.

const fs = require('fs')
const path = require('path')
const AdmZip = require('adm-zip')
const { PNG } = require('pngjs')
const puppeteer = require('puppeteer')

const [exportDir, clientJar, outDir = 'out', only = ''] = process.argv.slice(2)
if (!exportDir || !clientJar) {
	console.error('usage: node check.cjs <export dir> <minecraft client jar> [out dir]')
	process.exit(2)
}

const BLOCKBENCH = 'https://web.blockbench.net/'
// Views of the block, as camera offset and up direction: above, two corners, below.
const VIEWS = [[0, 26, 0.01, 0, 0, -1], [-30, 22, -38, 0, 1, 0], [30, 22, 38, 0, 1, 0], [0, -26, 0.01, 0, 0, -1]]
// A pixel counts as different past this much in any channel, and a look fails past this share.
const PIXEL_TOLERANCE = 40
const LOOK_TOLERANCE = 0.02
const CROP = 560

const exported = JSON.parse(fs.readFileSync(path.join(exportDir, 'looks.json'), 'utf8'))
const jar = new AdmZip(clientJar)
fs.mkdirSync(outDir, { recursive: true })

// --- Textures -----------------------------------------------------------------------------------

const keyOfJava = Object.fromEntries(Object.entries(exported.textures).map(([key, javaId]) => [javaId, key]))
const MISSING = (() => {
	const png = new PNG({ width: 2, height: 2 })
	png.data.set([255, 0, 255, 255, 0, 0, 0, 255, 0, 0, 0, 255, 255, 0, 255, 255])
	return PNG.sync.write(png)
})()

/** A texture as a data URL, first frame only (an animated one is a strip of square frames), made opaque. */
function textureUrl(javaId) {
	let bytes = null
	const key = keyOfJava[javaId]
	const own = key && path.join(exportDir, 'textures', key + '.png')
	if (own && fs.existsSync(own)) {
		bytes = fs.readFileSync(own)
	} else {
		const [namespace, id] = javaId.includes(':') ? javaId.split(':') : ['minecraft', javaId]
		const entry = jar.getEntry(`assets/${namespace}/textures/${id}.png`)
		if (entry) bytes = entry.getData()
	}
	if (!bytes) bytes = MISSING
	const png = PNG.sync.read(bytes)
	const frame = new PNG({ width: png.width, height: Math.min(png.width, png.height) })
	PNG.bitblt(png, frame, 0, 0, frame.width, frame.height, 0, 0)
	// Solid all over: Blockbench draws see-through pixels differently for Java and Bedrock models
	// (one lets them hide what's behind), so a plant would differ though its geometry doesn't.
	// Opaque, each plane's whole extent is compared instead.
	for (let i = 0; i < frame.data.length; i += 4) {
		const alpha = frame.data[i + 3] / 255
		frame.data[i] = Math.round(frame.data[i] * alpha + 255 * (1 - alpha))
		frame.data[i + 1] = Math.round(frame.data[i + 1] * alpha)
		frame.data[i + 2] = Math.round(frame.data[i + 2] * alpha + 255 * (1 - alpha))
		frame.data[i + 3] = 255
	}
	return 'data:image/png;base64,' + PNG.sync.write(frame).toString('base64')
}

// --- Java's rotations, as Minecraft applies them: x first, then y ------------------------------

// How many of Blockbench's quarter rolls make one of Java's quarter turns. A roll about x takes
// north to up and Java's x turn takes north to down, so three; about y both take north to east.
const ROLLS_PER_QUARTER = { x: 3, y: 1 }

/**
 * Java's own UVs for a face left to default: the box's bounds, seen from outside. What uvlock comes to
 * for a face whose UVs are these, once the box is turned: the texture stays put while the box moves.
 */
function positionalUv(direction, from, to) {
	const [x1, y1, z1] = from
	const [x2, y2, z2] = to
	switch (direction) {
		case 'down': return [x1, 16 - z2, x2, 16 - z1]
		case 'up': return [x1, z1, x2, z2]
		case 'north': return [16 - x2, 16 - y2, 16 - x1, 16 - y1]
		case 'south': return [x1, 16 - y2, x2, 16 - y1]
		case 'west': return [z1, 16 - y2, z2, 16 - y1]
		case 'east': return [16 - z2, 16 - y2, 16 - z1, 16 - y1]
	}
}

const same = (a, b) => a.length === b.length && a.every((v, i) => Math.abs(v - b[i]) < 1e-4)

/** All the pieces of a look as one Java model, each element with its piece's turn, or why it can't be drawn. */
function javaModel(look) {
	const pieces = look.java
	if (pieces.length === 0) return { skip: 'no blockstate file' }
	if (pieces.some(p => (p.z ?? 0) % 360 !== 0)) return { skip: 'a z turn, which this check has no verified turn for' }
	const textures = {}
	const elements = []
	const turns = []
	for (const [i, piece] of pieces.entries()) {
		const rename = name => `p${i}_${name}`
		for (const [name, id] of Object.entries(piece.model.textures)) textures[rename(name)] = id
		const quarters = axis => (((piece[axis] ?? 0) / 90) % 4 + 4) % 4
		const quarterTurned = quarters('x') % 2 === 1 || quarters('y') % 2 === 1
		for (const element of piece.model.elements) {
			const angledAxes = ['x', 'y', 'z'].filter(a => element.rotation?.[a]).length + (element.rotation?.angle ? 1 : 0)
			if (quarterTurned && angledAxes > 1) {
				// Swapping one axis's angle for another's is the whole of a quarter turn only for one axis:
				// with two, the order they apply in changes, which Blockbench's turn doesn't follow.
				return { skip: 'a quarter turn of an element angled on two axes' }
			}
			if (piece.uvlock) {
				if (element.rotation && element.rotation.angle) return { skip: 'uvlock on an angled element' }
				for (const [direction, face] of Object.entries(element.faces ?? {})) {
					if (face.uv && !same(face.uv, positionalUv(direction, element.from, element.to))) {
						return { skip: 'uvlock on a face whose UVs are its own, not its position' }
					}
				}
			}
			const copy = JSON.parse(JSON.stringify(element))
			for (const face of Object.values(copy.faces ?? {})) {
				if (face.texture?.startsWith('#')) face.texture = '#' + rename(face.texture.slice(1))
			}
			elements.push(copy)
			turns.push({ x: quarters('x') * ROLLS_PER_QUARTER.x % 4, y: quarters('y') * ROLLS_PER_QUARTER.y % 4, uvlock: !!piece.uvlock })
		}
	}
	if (elements.length === 0) return { skip: 'no elements: Bedrock draws nothing, or a stand-in box' }
	return { model: { textures, elements }, turns }
}

// --- Rendering ----------------------------------------------------------------------------------

/**
 * What a look's drawing depends on, with each texture replaced by a placeholder shared between the
 * Java model and the Bedrock materials. The conversion never looks inside a texture, so looks that
 * differ only in which textures fill the same faces (a door in every wood) convert alike, and one
 * stands for all. A Bedrock face given a different texture than its Java face changes the pattern,
 * so that look is still drawn.
 */
function drawingKey(look) {
	const placeholders = new Map()
	const placeholder = id => {
		if (!placeholders.has(id)) placeholders.set(id, `t${placeholders.size}`)
		return placeholders.get(id)
	}
	const sorted = object => Object.fromEntries(Object.entries(object).sort(([a], [b]) => a.localeCompare(b)))
	const java = look.java.map(piece => ({
		...piece,
		model: {
			...piece.model,
			textures: Object.fromEntries(Object.entries(sorted(piece.model.textures))
				.map(([name, id]) => [name, id.startsWith('#') ? id : placeholder(id)])),
		},
	}))
	const materials = Object.entries(sorted(look.materials))
		.map(([name, m]) => [name, placeholder(exported.textures[m.texture] ?? m.texture), m.render])
	return JSON.stringify([look.geometry, materials, java])
}

async function render(page, scene, view) {
	await page.evaluate(async (scene, view, positionalUvSource) => {
		const positionalUv = eval('(' + positionalUvSource + ')')
		if (Project) Project.close?.(true)
		newProject(scene.kind === 'java' ? Formats.java_block : Formats.bedrock_block)
		const urls = scene.textures
		if (scene.kind === 'java') {
			// Turns past Blockbench's size limit come back refused rather than drawn wrong.
			if (settings.deactivate_size_limit) settings.deactivate_size_limit.set(true)
			Codecs.java_block.parse(scene.model, 'model.json')
			Cube.all.forEach((cube, i) => {
				const turn = scene.turns[i]
				// UVs as parsed, kept through the turn: Blockbench would otherwise work them out again.
				cube.autouv = 0
				// One roll at a time: a roll of several steps moves the box by all of them but turns its
				// angles by only one.
				for (let n = 0; n < turn.x; n++) cube.roll(0, 1, [8, 8, 8])
				for (let n = 0; n < turn.y; n++) cube.roll(1, 1, [8, 8, 8])
				if (turn.uvlock) {
					for (const [direction, face] of Object.entries(cube.faces)) {
						face.uv = positionalUv(direction, cube.from, cube.to)
						face.rotation = 0
					}
				}
			})
			for (const tex of Texture.all) {
				if (urls[tex.id]) tex.fromDataURL(urls[tex.id])
				tex.render_sides = 'front'
				tex.updateMaterial()
			}
		} else {
			Codecs.bedrock.parse(scene.model, 'model.geo.json')
			const made = {}
			for (const [name, url] of Object.entries(urls)) {
				made[name] = new Texture({ name }).fromDataURL(url).add(false)
				// Drawn from both sides only where Bedrock would be: plain alpha_test and double_sided.
				const render = scene.render[name] ?? 'opaque'
				made[name].render_sides = render === 'alpha_test' || render === 'double_sided' ? 'double' : 'front'
				made[name].updateMaterial()
			}
			for (const cube of Cube.all) for (const dir in cube.faces) {
				const face = cube.faces[dir]
				if (made[face.material_name]) face.texture = made[face.material_name].uuid
			}
		}
		// Flat colour and no grid, so the two can be compared pixel for pixel.
		if (settings.shading) settings.shading.set(false)
		if (settings.grids) settings.grids.set(false)
		if (settings.base_grid) settings.base_grid.set(false)
		Canvas.updateAll()
		await new Promise(r => setTimeout(r, 300))
		const box = { min: [1e9, 1e9, 1e9], max: [-1e9, -1e9, -1e9] }
		for (const c of Cube.all) for (let a = 0; a < 3; a++) {
			box.min[a] = Math.min(box.min[a], c.from[a])
			box.max[a] = Math.max(box.max[a], c.to[a])
		}
		const centre = [0, 1, 2].map(a => (box.min[a] + box.max[a]) / 2)
		// Java block models are drawn 8 to the west and north of their coordinates.
		if (Format.id === 'java_block') { centre[0] -= 8; centre[2] -= 8 }
		const p = Preview.selected
		p.camera.up.set(view[3], view[4], view[5])
		p.camera.position.set(centre[0] + view[0], centre[1] + view[1], centre[2] + view[2])
		p.controls.target.set(centre[0], centre[1], centre[2])
		p.controls.update()
		p.render()
	}, scene, view, positionalUv.toString())
	await new Promise(r => setTimeout(r, 250))
	const canvas = await page.evaluateHandle(() => Preview.selected.canvas)
	return PNG.sync.read(await canvas.screenshot())
}

function crop(png) {
	const out = new PNG({ width: CROP, height: CROP })
	PNG.bitblt(png, out, Math.floor((png.width - CROP) / 2), Math.floor((png.height - CROP) / 2), CROP, CROP, 0, 0)
	return out
}

function difference(a, b) {
	let differing = 0
	for (let i = 0; i < a.data.length; i += 4) {
		if (Math.abs(a.data[i] - b.data[i]) > PIXEL_TOLERANCE || Math.abs(a.data[i + 1] - b.data[i + 1]) > PIXEL_TOLERANCE
			|| Math.abs(a.data[i + 2] - b.data[i + 2]) > PIXEL_TOLERANCE) differing++
	}
	return differing / (a.width * a.height)
}

function sideBySide(pairs) {
	const out = new PNG({ width: CROP * 2, height: CROP * pairs.length })
	pairs.forEach(([java, bedrock], row) => {
		PNG.bitblt(java, out, 0, 0, CROP, CROP, 0, row * CROP)
		PNG.bitblt(bedrock, out, 0, 0, CROP, CROP, CROP, row * CROP)
	})
	return PNG.sync.write(out)
}

// --- The run ------------------------------------------------------------------------------------

;(async () => {
	const browser = await puppeteer.launch({ headless: 'shell', args: ['--no-sandbox', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] })
	const page = await browser.newPage()
	await page.setViewport({ width: 2200, height: 900 })
	await page.goto(BLOCKBENCH, { waitUntil: 'networkidle2', timeout: 120000 })
	await new Promise(r => setTimeout(r, 3000))

	const report = ['| State | Result | Differing |', '|---|---|---|']
	let failed = 0
	let checked = 0
	// Each way of drawing is compared once: see drawingKey.
	const compared = new Set()
	for (const look of exported.looks) {
		if (only && !look.state.includes(only)) continue
		const drawing = drawingKey(look)
		if (compared.has(drawing)) continue
		compared.add(drawing)
		const java = javaModel(look)
		if (java.skip) {
			report.push(`| \`${look.state}\` | skipped: ${java.skip} | |`)
			continue
		}
		const javaUrls = Object.fromEntries(Object.entries(java.model.textures).map(([name, id]) => [name, textureUrl(id)]))
		const bedrockUrls = Object.fromEntries(Object.entries(look.materials).map(([name, m]) => [name, textureUrl(exported.textures[m.texture] ?? m.texture)]))
		const renderMethods = Object.fromEntries(Object.entries(look.materials).map(([name, m]) => [name, m.render]))
		const javaScene = { kind: 'java', model: java.model, textures: javaUrls, turns: java.turns }
		const bedrockScene = { kind: 'bedrock', model: look.geometry, textures: bedrockUrls, render: renderMethods }

		const pairs = []
		let worst = 0
		for (const view of VIEWS) {
			const a = crop(await render(page, javaScene, view))
			const b = crop(await render(page, bedrockScene, view))
			worst = Math.max(worst, difference(a, b))
			pairs.push([a, b])
		}
		checked++
		const pass = worst <= LOOK_TOLERANCE
		if (!pass) {
			failed++
			const name = look.state.replace(/[^a-z0-9_=,-]+/gi, '_')
			fs.writeFileSync(path.join(outDir, name + '.png'), sideBySide(pairs))
		}
		report.push(`| \`${look.state}\` | ${pass ? 'same' : '**differs**'} | ${(worst * 100).toFixed(1)}% |`)
		console.log(`${pass ? 'same   ' : 'DIFFERS'} ${(worst * 100).toFixed(1).padStart(5)}%  ${look.state}`)
	}
	await browser.close()

	report.unshift(`${checked} looks compared, ${failed} differ. Java on the left, Bedrock on the right, in the images.`, '')
	fs.writeFileSync(path.join(outDir, 'report.md'), report.join('\n') + '\n')
	console.log(`${checked} compared, ${failed} differ; report in ${path.join(outDir, 'report.md')}`)
	process.exit(failed)
})().catch(e => { console.error(e.stack); process.exit(1) })
