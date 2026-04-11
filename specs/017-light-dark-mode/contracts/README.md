# Contracts: Light & Dark Mode Theme Support

This feature has **no API contracts**. It is entirely a frontend-only feature with no backend API changes.

## Client-Side Contract

### localStorage Interface

**Key**: `theme-preference`
**Type**: `string | null`
**Valid values**: `"light"`, `"dark"`, or absent (null)

### HTML Contract

**Attribute**: `data-theme` on `<html>` element
**Valid values**: `"light"` or absent (dark is default/no attribute)

### ThemeContext Interface

```typescript
interface ThemeContextValue {
  theme: 'light' | 'dark'
  toggleTheme: () => void
}
```
