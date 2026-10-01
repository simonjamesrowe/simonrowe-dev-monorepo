import type { ProjectChapter } from '../../types/portfolio'

/**
 * The plain-text forms the portfolio editor uses for its two list fields: example questions
 * one per line, and demo chapters as `m:ss Label` one per line. Kept as text while editing, so
 * typing a new line is not undone by turning it into a list on every keystroke.
 */

export function questionsToText(questions: string[]): string {
  return questions.join('\n')
}

export function textToQuestions(text: string): string[] {
  return text.split('\n').map(line => line.trim()).filter(line => line.length > 0)
}

export function chaptersToText(chapters: ProjectChapter[]): string {
  return chapters
    .map(chapter => {
      const minutes = Math.floor(chapter.startSeconds / 60)
      const seconds = String(chapter.startSeconds % 60).padStart(2, '0')
      return `${minutes}:${seconds} ${chapter.label}`
    })
    .join('\n')
}

/** `1:05 Admin console`. No nested quantifiers, and each line is a short line typed by hand. */
const CHAPTER_LINE = /^(\d{1,3}):([0-5]\d)\s+(\S.*)$/

export type ChapterParse =
  | { ok: true; chapters: ProjectChapter[] }
  | { ok: false; line: number }

export function textToChapters(text: string): ChapterParse {
  const chapters: ProjectChapter[] = []
  const lines = text.split('\n')
  for (let index = 0; index < lines.length; index++) {
    const line = lines[index].trim()
    if (!line) continue
    const match = CHAPTER_LINE.exec(line)
    if (!match) return { ok: false, line: index + 1 }
    chapters.push({ startSeconds: Number(match[1]) * 60 + Number(match[2]), label: match[3].trim() })
  }
  return { ok: true, chapters }
}
