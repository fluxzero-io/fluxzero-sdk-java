Fluxzero lets developers build backend applications with domain messages instead of hand-written infrastructure plumbing. Navigate these docs like a graph: choose the closest topic, then follow parent, sibling, or detail links only when they help the current edit.

This is the SDK documentation graph. Its content can be read locally or served through MCP; transport authentication and development controls belong to the hosting tool. Documentation does not itself grant login, package-publication or deployment capabilities. Select the graph matching the project SDK and reuse unchanged articles rather than loading the entire corpus.

For application work, prefer this order:

1. Read the SDK overview.
2. Model commands and independent `@Model` state, legal assertions, and handlers.
3. Test behavior locally with `TestFixture`.
4. Add search/read models and thin web endpoints.
5. Read cloud pages only when the user asks about publishing or demo deployment.

This checkout documents SDK v2 (Java 25+). For new state, start with Models and Graphs. Retained aggregate articles
serve existing persisted state; they are not a recommendation to introduce aggregates in new v2 code.
