import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import { SkipLink } from "./SkipLink";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <SkipLink />
    <App />
  </StrictMode>,
);
