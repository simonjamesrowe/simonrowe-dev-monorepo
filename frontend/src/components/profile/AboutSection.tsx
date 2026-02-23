import ReactMarkdown from 'react-markdown'
import rehypeSanitize from 'rehype-sanitize'

interface AboutSectionProps {
  description: string
}

export function AboutSection({ description }: AboutSectionProps) {
  return (
    <section className="panel" id="about">
      <h3>About</h3>
      <ReactMarkdown
        rehypePlugins={[rehypeSanitize]}
        components={{
          a: ({ href, children }) => (
            <a href={href} target="_blank" rel="noopener noreferrer">
              {children}
            </a>
          ),
        }}
      >
        {description}
      </ReactMarkdown>
    </section>
  )
}
