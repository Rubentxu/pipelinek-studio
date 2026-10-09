/**
 * S5 E5.6 — Skip-to-main-content link. The first focusable element in the
 * page; visible only when focused (Tab into the page). Lets keyboard
 * users bypass the header + intro paragraph to land directly on the
 * main content. Standard a11y pattern documented in
 * https://www.w3.org/WAI/WCAG21/Techniques/general/G1
 */
export function SkipLink() {
  return (
    <a
      href="#main-content"
      style={{
        position: "absolute",
        left: -9999,
        top: 0,
        zIndex: 9999,
        background: "#000",
        color: "#fff",
        padding: "0.5rem 1rem",
        textDecoration: "none",
      }}
      onFocus={(e) => {
        e.currentTarget.style.left = "0";
      }}
      onBlur={(e) => {
        e.currentTarget.style.left = "-9999px";
      }}
    >
      Skip to main content
    </a>
  );
}
