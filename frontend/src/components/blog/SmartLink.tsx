import type { ComponentProps } from 'react'
import { Link } from 'react-router-dom'

type AnchorProps = ComponentProps<'a'>

function isExternal(href: string): boolean {
  return href.startsWith('http') && !href.includes('simonrowe.dev')
}

export function SmartLink({ href, children, ...rest }: AnchorProps) {
  if (!href) {
    return <a {...rest}>{children}</a>
  }

  if (isExternal(href)) {
    return (
      <a href={href} rel="noopener noreferrer" target="_blank" {...rest}>
        {children}
      </a>
    )
  }

  return (
    <Link to={href} {...rest}>
      {children}
    </Link>
  )
}
