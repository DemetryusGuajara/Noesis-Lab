# Noesis Lab — docs (GitHub Pages)

Step-by-step API key tutorials, multilingual (English primary).

## How to publish

1. Go to the repository on GitHub → **Settings → Pages**.
2. Under **Build and deployment**, choose **Deploy from a branch**.
3. Select branch **main** and folder **/docs**, then **Save**.
4. The site will be live at `https://<user>.github.io/Noesis-Lab/` in a minute or two.

## Structure

- `index.html` — language selector.
- `en/` — English guides (primary): index + one page per provider.
- `pt/` — Portuguese guides: index + one page per provider.
- `es/`, `fr/` — stubs (coming soon).
- `assets/style.css` — shared stylesheet (no build step, plain static HTML).

## Editing

Each provider page follows the same template: locked endpoint → key creation steps
(with links to the official docs) → how to use it in the app → official links.
Screenshots: add `<img>` tags where marked `<!-- screenshot -->`.
