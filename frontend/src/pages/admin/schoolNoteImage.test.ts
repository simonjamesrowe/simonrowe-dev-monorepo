import { afterEach, describe, expect, it, vi } from 'vitest'

import { prepareSchoolNoteImage } from './schoolNoteImage'

describe('prepareSchoolNoteImage', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('scales the longest edge to 2000 pixels and emits a compressed JPEG', async () => {
    installImage(4000, 1000)
    const drawImage = vi.fn()
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext')
      .mockReturnValue({ drawImage } as unknown as CanvasRenderingContext2D)
    const prepared = new Blob(['jpeg'], { type: 'image/jpeg' })
    let encodedSize: { width: number; height: number } | undefined
    const toBlob = vi.spyOn(HTMLCanvasElement.prototype, 'toBlob')
      .mockImplementation(function (this: HTMLCanvasElement, callback) {
        encodedSize = { width: this.width, height: this.height }
        callback(prepared)
      })

    await expect(prepareSchoolNoteImage('blob:photo')).resolves.toBe(prepared)

    expect(encodedSize?.width).toBe(2000)
    expect(encodedSize?.height).toBe(500)
    expect(drawImage).toHaveBeenCalledWith(expect.anything(), 0, 0, 2000, 500)
    expect(toBlob).toHaveBeenCalledWith(expect.any(Function), 'image/jpeg', 0.85)
  })

  it('does not enlarge an image already inside the limit', async () => {
    installImage(1200, 1600)
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext')
      .mockReturnValue({ drawImage: vi.fn() } as unknown as CanvasRenderingContext2D)
    let encodedSize: { width: number; height: number } | undefined
    vi.spyOn(HTMLCanvasElement.prototype, 'toBlob')
      .mockImplementation(function (this: HTMLCanvasElement, callback) {
        encodedSize = { width: this.width, height: this.height }
        callback(new Blob(['jpeg']))
      })

    await prepareSchoolNoteImage('blob:small')

    expect(encodedSize?.width).toBe(1200)
    expect(encodedSize?.height).toBe(1600)
  })

  it('rejects when the browser cannot create a drawing context', async () => {
    installImage(100, 100)
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null)

    await expect(prepareSchoolNoteImage('blob:no-context')).rejects.toThrow(
      'This browser could not prepare that photo.',
    )
  })

  it('rejects when JPEG encoding returns no data', async () => {
    installImage(100, 100)
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext')
      .mockReturnValue({ drawImage: vi.fn() } as unknown as CanvasRenderingContext2D)
    vi.spyOn(HTMLCanvasElement.prototype, 'toBlob')
      .mockImplementation((callback) => callback(null))

    await expect(prepareSchoolNoteImage('blob:no-data')).rejects.toThrow(
      'This browser could not prepare that photo.',
    )
  })

  it('rejects an image the browser cannot open', async () => {
    installImage(0, 0, true)

    await expect(prepareSchoolNoteImage('blob:broken')).rejects.toThrow(
      'That photo could not be opened.',
    )
  })
})

function installImage(width: number, height: number, fail = false) {
  vi.stubGlobal('Image', class {
    naturalWidth = width
    naturalHeight = height
    onload: (() => void) | null = null
    onerror: (() => void) | null = null

    set src(_value: string) {
      if (fail) {
        this.onerror?.()
      } else {
        this.onload?.()
      }
    }
  })
}
