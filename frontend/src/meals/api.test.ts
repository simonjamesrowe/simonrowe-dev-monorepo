import { afterEach, describe, expect, it, vi } from 'vitest'

import { createMealsApi, MealsApiError } from './api'

describe('meals api', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('sends the bearer token on every call, photos included', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(new Blob(['x']), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const api = createMealsApi(async () => 'token-123')

    await api.image('abc.png')

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/meals/images/abc.png')
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer token-123')
  })

  it('surfaces the server message on a failed call', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ message: 'No such recipe' }), { status: 404 })))
    const api = createMealsApi(async () => 't')

    await expect(api.rate({ recipeId: 'x', score: 3, again: true, tags: [], notes: '' }))
      .rejects.toEqual(expect.objectContaining({ status: 404, message: 'No such recipe' }))
    await expect(api.ratings()).rejects.toBeInstanceOf(MealsApiError)
  })

  it('uploads a bundle as multipart form data', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('{"recipesAdded":1}', { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const api = createMealsApi(async () => 't')

    const report = await api.importBundle(new File(['zip'], 'bundle.zip'))

    expect(report.recipesAdded).toBe(1)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/meals/admin/bundles')
    expect(init.body).toBeInstanceOf(FormData)
  })
})
