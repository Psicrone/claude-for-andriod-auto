// Turns Claude's Markdown replies into something that sounds right through a car speaker.

const MAX_SPOKEN_CHARS = 1200;

export function toSpeech(markdown: string, maxChars = MAX_SPOKEN_CHARS): string {
  let text = markdown;

  // Code is useless read aloud; say that it exists instead.
  text = text.replace(/```[\s\S]*?```/g, " (code block omitted) ");
  text = text.replace(/`([^`]+)`/g, "$1");

  // Links and images: keep the label, drop the URL.
  text = text.replace(/!\[([^\]]*)\]\([^)]*\)/g, "$1");
  text = text.replace(/\[([^\]]+)\]\([^)]*\)/g, "$1");
  text = text.replace(/https?:\/\/\S+/g, "a link");

  // Headings, emphasis, quotes, rules, table pipes.
  text = text.replace(/^#{1,6}\s+/gm, "");
  text = text.replace(/(\*\*|__)(.*?)\1/g, "$2");
  text = text.replace(/(^|\W)[*_]([^*_\n]+)[*_](?=\W|$)/g, "$1$2");
  text = text.replace(/^>\s?/gm, "");
  text = text.replace(/^\s*([-*_]\s*){3,}$/gm, "");
  text = text.replace(/^\s*\|?[-:\s|]+\|[-:\s|]*$/gm, "");
  text = text.replace(/\|/g, ", ");

  // List markers become sentence breaks so TTS pauses between items.
  text = text.replace(/^\s*(?:[-*+]|\d+[.)])\s+(.*)$/gm, (_m, item: string) =>
    /[.!?:]$/.test(item.trim()) ? item : `${item}.`,
  );

  text = text.replace(/[ \t]+/g, " ").replace(/\s*\n\s*/g, " ").trim();

  if (text.length > maxChars) {
    const cut = text.slice(0, maxChars);
    const lastStop = Math.max(cut.lastIndexOf(". "), cut.lastIndexOf("? "), cut.lastIndexOf("! "));
    text = `${lastStop > maxChars / 2 ? cut.slice(0, lastStop + 1) : cut} The rest is on your phone.`;
  }
  return text;
}

/** One short phrase describing a tool call, for progress updates and approval prompts. */
export function describeTool(toolName: string, input: Record<string, unknown>): string {
  const str = (key: string) => (typeof input[key] === "string" ? (input[key] as string) : "");
  const file = (p: string) => p.split("/").filter(Boolean).pop() ?? p;
  const clip = (s: string, n = 80) => (s.length > n ? `${s.slice(0, n)}…` : s);

  switch (toolName) {
    case "Bash":
      return `run the command ${clip(str("command"))}`;
    case "Edit":
    case "MultiEdit":
      return `edit ${file(str("file_path"))}`;
    case "Write":
      return `write ${file(str("file_path"))}`;
    case "Read":
      return `read ${file(str("file_path"))}`;
    case "Glob":
    case "Grep":
      return `search for ${clip(str("pattern"), 40)}`;
    case "WebFetch":
      return `fetch a web page`;
    case "WebSearch":
      return `search the web for ${clip(str("query"), 60)}`;
    case "Task":
    case "Agent":
      return `start a sub-agent: ${clip(str("description"), 60)}`;
    default:
      return `use ${toolName}`;
  }
}
