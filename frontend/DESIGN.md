# Interface design

SecurityHub uses IBM Plex Sans, a dark navigation rail, neutral content surfaces and
teal actions. Severity and status retain their own colours and explicit labels.

## Shared rules

- Theme tokens, the Material theme and shared table, filter, heading and form styles
  live in `src/styles.scss`.
- Navigation stays visible on desktop and becomes an overlay drawer below 960px.
  The toolbar belongs inside the content region so the drawer can cover it.
- Use spacing, type size and dividers to establish hierarchy. Avoid decorative
  gradients, glows and elevated cards. Corner radii range from 4px to 8px.
- Keep creation forms within a readable width, with actions below the fields and
  separated by a divider.
- Tables scroll inside their own container. Its `position: relative` also contains
  absolutely positioned descendants, which would otherwise widen the page.
- Dashboard indicators share one surface and use aligned, tabular figures. When a
  link cannot reproduce an aggregate exactly, keep the explanation available to
  screen readers and in the indicator tooltip.
- Read chart and component colours from tokens so both themes remain consistent.
- Preserve visible keyboard focus, action targets of at least 44px, field labels
  and reduced-motion support.

## Validation

Run `npm run build`, `npm run lint` and `npm run test:ci`. Review populated tables,
empty states, forms and details at 375px, 768px, 1024px and 1440px. Check both themes,
mobile navigation and increased text size.

The browser review for this redesign used simulated API responses. Those fixtures
were confined to the review browser and are not part of the application or a
replacement for integration tests against the backend.
