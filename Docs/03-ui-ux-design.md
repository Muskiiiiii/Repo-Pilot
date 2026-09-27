# RepoPilot

## UI/UX Design Document

**Project:** RepoPilot — AI-Powered Software Engineering Platform
**Document Type:** UI/UX Design Specification
**Version:** 1.0
**Status:** Design Specification

---

# 1. Design Vision

RepoPilot is an AI-powered software engineering platform.

The UI should communicate:

- Developer-first

- Technical

- Intelligent

- Reliable

- Professional

- Minimal

- Fast

- Explainable

- Trustworthy


The interface must feel like a **professional engineering tool**, not an AI chatbot.

---

# 2. Design Principles

## 2.1 Developer First

The interface should prioritize:

- Code

- Diffs

- Tasks

- AI reasoning

- Tests

- Pull Requests

- Repository context


Avoid unnecessary decorative UI.

---

## 2.2 AI Should Be Explainable

Whenever AI makes an important decision, the UI should show:

```text
What AI did
Why AI did it
What evidence it used
How confident it is
What happens next
```

---

## 2.3 Human Control

The user must remain in control of repository modifications.

Important AI actions should have clear:

```text
Approve
Reject
Review
Retry
Cancel
```

actions.

---

## 2.4 Progressive Disclosure

Do not show everything at once.

Example:

```text
Task
 ↓
Summary
 ↓
Details
 ↓
Advanced Details
```

Users should be able to inspect deeper information when needed.

---

## 2.5 Clear System State

Every long-running operation must clearly communicate its state.

Examples:

```text
Indexing...
Analyzing...
Planning...
Generating...
Testing...
Reviewing...
Waiting for approval...
Completed
Failed
```

---

# 3. Visual Direction

## 3.1 Overall Style

The UI should use:

- Clean layouts

- Strong typography

- Subtle borders

- Small corner radius

- Compact spacing

- Code-editor-inspired components

- Minimal shadows

- High information density


Avoid:

- Excessive gradients

- Giant cards

- Excessive animations

- Cartoonish AI graphics

- Unnecessary illustrations


---

# 4. Theme

Primary theme:

```text
Dark-first developer interface
```

The application should support:

```text
Dark Mode
Light Mode
```

Dark mode should be the default.

---

# 5. Color System

Use semantic colors rather than hardcoding colors throughout components.

Example semantic tokens:

```text
Background
Surface
Surface Elevated
Border
Text Primary
Text Secondary
Text Muted

Success
Warning
Error
Info
AI Accent
```

Example usage:

```text
Success → tests passed
Warning → potential issue
Error → execution failed
Info → system information
AI Accent → AI-generated content
```

The exact color values should be defined in the design system.

---

# 6. Typography

Primary font:

```text
Inter
```

Fallback:

```text
system-ui
```

Code font:

```text
JetBrains Mono
```

Typography hierarchy:

```text
Display
H1
H2
H3
Body
Small
Caption
Code
```

---

# 7. Layout System

Use a consistent spacing scale.

Recommended base:

```text
4px
8px
12px
16px
20px
24px
32px
40px
48px
64px
```

---

# 8. Application Shell

The authenticated application should use:

```text
┌────────────────────────────────────────────────────┐
│ Top Bar                                             │
├────────────┬───────────────────────────────────────┤
│            │                                       │
│ Sidebar    │             Main Content              │
│            │                                       │
│            │                                       │
│            │                                       │
└────────────┴───────────────────────────────────────┘
```

---

# 9. Top Navigation

The top bar should contain:

```text
RepoPilot
Project Selector
Search
Notifications
Help
User Avatar
```

Example:

```text
┌─────────────────────────────────────────────────────────────┐
│ RepoPilot   E-Commerce Backend   🔍 Search    🔔   Y        │
└─────────────────────────────────────────────────────────────┘
```

---

# 10. Sidebar Navigation

Main navigation:

```text
Overview

Projects

Tasks

Repositories

Pull Requests

AI Runs

Activity

Settings
```

Project-specific navigation:

```text
Overview
Tasks
Codebase
AI Runs
Pull Requests
Activity
Settings
```

The active navigation item should be visually obvious.

---

# 11. Global Search

Users should be able to search:

- Projects

- Tasks

- Repositories

- Pull Requests

- Files

- AI runs


Search should support keyboard shortcuts.

Example:

```text
⌘ K
```

or:

```text
Ctrl + K
```

Search modal:

```text
┌─────────────────────────────────────┐
│ 🔍 Search RepoPilot...              │
├─────────────────────────────────────┤
│ Recent                              │
│                                     │
│ Tasks                               │
│ Repositories                        │
│ Pull Requests                       │
└─────────────────────────────────────┘
```

---

# 12. Authentication UI

## 12.1 Login

Screen:

```text
┌─────────────────────────────────┐
│             RepoPilot           │
│                                 │
│       Welcome back              │
│                                 │
│ Email                           │
│ ┌─────────────────────────────┐ │
│ │                             │ │
│ └─────────────────────────────┘ │
│                                 │
│ Password                        │
│ ┌─────────────────────────────┐ │
│ │                             │ │
│ └─────────────────────────────┘ │
│                                 │
│        [ Sign In ]              │
│                                 │
│ ─────────── OR ───────────────  │
│                                 │
│ [ Continue with GitHub ]        │
│                                 │
└─────────────────────────────────┘
```

---

# 13. GitHub Connection

After login:

```text
Connect GitHub
```

UI:

```text
┌───────────────────────────────────────┐
│ Connect GitHub                         │
│                                       │
│ RepoPilot needs access to repositories│
│ you authorize.                        │
│                                       │
│ Permissions                           │
│ ✓ Read repositories                   │
│ ✓ Read issues                         │
│ ✓ Create branches                     │
│ ✓ Create Pull Requests                │
│                                       │
│       [ Connect GitHub ]              │
└───────────────────────────────────────┘
```

The user should clearly understand what permissions are being requested.

---

# 14. Dashboard

The dashboard is the user's home screen.

## 14.1 Dashboard Structure

```text
┌──────────────────────────────────────────────┐
│ Good morning, Yash                           │
│ Here's what's happening with your projects.  │
├──────────────────────────────────────────────┤
│                                              │
│ Projects    Active Tasks    PRs    Success   │
│    5            3            12      91%     │
│                                              │
├──────────────────────────────────────────────┤
│ Active AI Tasks                              │
│                                              │
│ Add JWT Authentication        Running        │
│ Fix payment timeout           Reviewing      │
│                                              │
├──────────────────────────────────────────────┤
│ Recent Projects                             │
│                                              │
│ E-Commerce Backend                           │
│ Payment Service                              │
│ AI Platform                                  │
└──────────────────────────────────────────────┘
```

---

# 15. Dashboard Metrics

Display:

```text
Total Projects
Active Tasks
Completed Tasks
Pull Requests
Test Success Rate
AI Success Rate
```

Metrics should be clickable.

---

# 16. Project Creation

Create Project modal:

```text
┌─────────────────────────────────────┐
│ Create Project                      │
│                                     │
│ Project Name                        │
│ [____________________________]      │
│                                     │
│ Description                         │
│ [____________________________]      │
│ [____________________________]      │
│                                     │
│ Repository                          │
│ [ Select GitHub Repository ▼ ]      │
│                                     │
│ Branch                              │
│ [ main ▼ ]                          │
│                                     │
│       [Cancel] [Create Project]     │
└─────────────────────────────────────┘
```

---

# 17. Project Overview

Project header:

```text
E-Commerce Backend
github.com/user/ecommerce-backend

● Indexed
main
```

Actions:

```text
[ New Task ]
[ Re-index ]
[ Settings ]
```

---

# 18. Project Overview Layout

```text
┌───────────────────────────────────────────────────┐
│ E-Commerce Backend                                │
│ GitHub Repository                                 │
│                                                   │
│ [ New Task ] [ Re-index ]                        │
├───────────────────────────────────────────────────┤
│                                                   │
│ Tasks       PRs       AI Runs       Test Success  │
│  32         21          48              94%       │
│                                                   │
├───────────────────────────────────────────────────┤
│ Active Tasks                                      │
│                                                   │
│ Add authentication          Testing               │
│ Fix payment bug             Review                │
│                                                   │
├───────────────────────────────────────────────────┤
│ Recent Activity                                   │
│                                                   │
│ PR #42 created                                    │
│ Task completed                                    │
│ Repository indexed                                │
└───────────────────────────────────────────────────┘
```

---

# 19. Repository Page

The repository page should show:

```text
Repository Information
Branch
Last Indexed Commit
Index Status
Languages
File Count
```

Example:

```text
Repository

ecommerce-backend

Branch:
main

Commit:
a82fd12

Index:
✓ Up to date

Languages:
Java 72%
TypeScript 18%
SQL 10%
```

---

# 20. Repository Code Explorer

Provide a simplified code explorer.

```text
┌──────────────┬───────────────────────────────┐
│ Files        │ Code                          │
│              │                               │
│ src/         │ AuthService.java              │
│ ├ auth/      │                               │
│ ├ user/      │ public class AuthService {    │
│ └ payment/   │                               │
│              │   ...                         │
│ tests/       │ }                             │
└──────────────┴───────────────────────────────┘
```

This is for understanding/searching the repository, not intended to replace a full IDE.

---

# 21. Code Search

Search interface:

```text
┌─────────────────────────────────────────────┐
│ 🔍 Search code...                           │
└─────────────────────────────────────────────┘
```

Results:

```text
AuthService.java
src/auth/AuthService.java:42

UserController.java
src/user/UserController.java:81
```

Search results should show:

- File.

- Line.

- Matching code.

- Relevance.


---

# 22. New Task

Primary CTA:

```text
+ New Task
```

Task creation UI:

```text
┌───────────────────────────────────────────────┐
│ Create Engineering Task                       │
│                                               │
│ Title                                         │
│ [ Add JWT authentication                  ]   │
│                                               │
│ Description                                   │
│ ┌───────────────────────────────────────────┐ │
│ │ Implement JWT authentication for the API. │ │
│ │                                           │ │
│ └───────────────────────────────────────────┘ │
│                                               │
│ Branch                                        │
│ [ main ▼ ]                                    │
│                                               │
│        [Cancel] [Create Task]                 │
└───────────────────────────────────────────────┘
```

---

# 23. Task Detail Page

This is one of the **most important screens**.

The page should feel like an engineering workspace.

```text
┌──────────────────────────────────────────────────────────┐
│ Add JWT Authentication                       ● Running    │
│ #TASK-124                                                │
├──────────────────────────────────────────────────────────┤
│ Issue │ Plan │ Changes │ Tests │ Review │ PR             │
├──────────────────────────────────────────────────────────┤
│                                                          │
│ Main Content                                             │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

---

# 24. Task Progress Timeline

Display workflow state:

```text
✓ Issue analyzed
✓ Codebase searched
✓ Plan generated
✓ Plan approved
✓ Code generated
⏳ Running tests
○ Code review
○ Pull Request
```

Each step should be expandable.

---

# 25. AI Activity Panel

Show live AI activity.

```text
┌────────────────────────────────────┐
│ AI Activity                        │
├────────────────────────────────────┤
│ ✓ Searching repository             │
│ ✓ Found 12 relevant files          │
│ ✓ Analyzed SecurityConfig          │
│ ✓ Created implementation plan      │
│ ⏳ Generating changes...            │
└────────────────────────────────────┘
```

Do not expose hidden chain-of-thought.

Only show concise, user-facing explanations and tool/action summaries.

---

# 26. Issue Understanding UI

Display:

```text
AI Understanding

Objective
Add JWT authentication.

Requirements

✓ Login endpoint
✓ JWT generation
✓ JWT validation
✓ Protected endpoints

Affected Areas

Security
Authentication
User API
```

Include:

```text
Confidence: 91%
```

---

# 27. Plan Review UI

This is the first major approval screen.

```text
┌─────────────────────────────────────────────┐
│ Implementation Plan                         │
├─────────────────────────────────────────────┤
│                                             │
│ 1. Create JwtService                        │
│                                             │
│ 2. Add login endpoint                       │
│                                             │
│ 3. Configure authentication filter           │
│                                             │
│ 4. Protect API endpoints                    │
│                                             │
│ 5. Add unit tests                           │
│                                             │
├─────────────────────────────────────────────┤
│ Risks                                       │
│                                             │
│ Existing authentication layer detected.    │
│                                             │
├─────────────────────────────────────────────┤
│                                             │
│ [ Reject ]              [ Approve Plan ]    │
└─────────────────────────────────────────────┘
```

---

# 28. Plan Details

Each plan step should be expandable.

Example:

```text
2. Add Login Endpoint
   │
   ├── File: AuthController.java
   ├── Endpoint: POST /api/auth/login
   └── Dependency: JwtService
```

---

# 29. Code Changes Screen

After AI generates code:

```text
┌───────────────────────────────────────────────────────┐
│ Proposed Changes                                       │
├──────────────────┬────────────────────────────────────┤
│ Files Changed    │ Diff                               │
│                  │                                    │
│ M SecurityConfig │ - old code                         │
│ A JwtService     │ + new code                         │
│ M AuthController │ + authentication logic              │
│ A JwtServiceTest │ + tests                            │
└──────────────────┴────────────────────────────────────┘
```

---

# 30. File Change Indicators

Use:

```text
A = Added
M = Modified
D = Deleted
```

Example:

```text
A  JwtService.java
M  SecurityConfig.java
M  AuthController.java
A  JwtServiceTest.java
```

---

# 31. Diff Viewer

The diff viewer should support:

- Syntax highlighting.

- Line numbers.

- Added/removed lines.

- File navigation.

- Expand/collapse.

- Side-by-side view.

- Unified view.


User preference:

```text
[ Split ] [ Unified ]
```

---

# 32. AI Change Explanation

Every significant modification should be explainable.

Example:

```text
Why this change?

SecurityConfig.java

Added JWT authentication filter.

Reason:
The existing application uses Spring Security.
The filter is required to validate JWT tokens
before protected requests reach controllers.

Evidence:
SecurityConfig.java
AuthController.java
UserService.java
```

---

# 33. Change Confidence

Display:

```text
Confidence
91% High
```

Possible levels:

```text
High
Medium
Low
```

Do not imply that confidence guarantees correctness.

---

# 34. File-Level Approval

Where practical:

```text
[ Approve File ]
[ Reject File ]
```

Overall:

```text
[ Reject Changes ]
[ Approve Changes ]
```

---

# 35. Testing Screen

Testing should have a dedicated view.

```text
┌───────────────────────────────────────────────────┐
│ Tests                                             │
├───────────────────────────────────────────────────┤
│                                                   │
│ ● Passed                                          │
│                                                   │
│ Total       Passed      Failed      Duration      │
│ 42          42          0           8.4s          │
│                                                   │
├───────────────────────────────────────────────────┤
│ ✓ AuthServiceTest                                 │
│ ✓ JwtFilterTest                                   │
│ ✓ LoginControllerTest                             │
│ ✓ UserControllerTest                              │
└───────────────────────────────────────────────────┘
```

---

# 36. Test Failure UI

If tests fail:

```text
┌───────────────────────────────────────────────┐
│ ✕ 3 Tests Failed                              │
├───────────────────────────────────────────────┤
│ AuthServiceTest                               │
│                                               │
│ Expected: 200                                 │
│ Received: 401                                 │
│                                               │
│ AI Analysis                                   │
│ JWT filter is rejecting the request before   │
│ the authentication mock is applied.          │
│                                               │
│ [ View Logs ] [ Apply AI Fix ]                │
└───────────────────────────────────────────────┘
```

---

# 37. Self-Correction UI

When AI automatically attempts a fix:

```text
AI Recovery

Attempt 1 / 3

Tests failed:
3

Analyzing failure...

✓ Root cause identified
✓ Fix generated
⏳ Running tests again...
```

If successful:

```text
✓ Fix successful

Attempt 2 / 3

42 / 42 tests passed.
```

---

# 38. Code Review Screen

Display:

```text
┌─────────────────────────────────────────────┐
│ AI Code Review                              │
├─────────────────────────────────────────────┤
│                                             │
│ Overall Score                 8.7 / 10      │
│                                             │
│ Correctness                    ✓            │
│ Security                       ✓            │
│ Performance                    ✓            │
│ Maintainability                ✓            │
│ Testing                        ✓            │
│                                             │
├─────────────────────────────────────────────┤
│ Warnings                                    │
│                                             │
│ ⚠ JWT expiration is hardcoded.              │
│                                             │
│ ⚠ Exception handling could be improved.    │
└─────────────────────────────────────────────┘
```

---

# 39. Security Review

Security findings should be visually separated.

Example:

```text
Security Review

✓ No critical issues
✓ No secrets detected
✓ No unsafe file operations

Warnings:
⚠ User input validation could be improved.
```

Critical issues must be visually prominent.

---

# 40. Final Approval Screen

Before GitHub write operations:

```text
┌─────────────────────────────────────────────┐
│ Ready to Create Pull Request                │
├─────────────────────────────────────────────┤
│                                             │
│ Files Changed             7                 │
│ Tests                     42 / 42 passed    │
│ Code Review               8.7 / 10         │
│ Security                  No critical issue │
│ AI Confidence             91%              │
│                                             │
│ Branch                                    │
│ repopilot/add-jwt-auth                     │
│                                             │
│ [ Cancel ]          [ Approve & Create PR ]│
└─────────────────────────────────────────────┘
```

---

# 41. Pull Request Screen

Display:

```text
Pull Request

#142 Add JWT Authentication

Status:
● Open

Branch:
repopilot/add-jwt-auth

Tests:
✓ Passed

AI Review:
8.7 / 10

CI:
✓ Passed
```

CTA:

```text
[ Open on GitHub ]
```

---

# 42. Pull Request Timeline

Example:

```text
✓ Branch created
✓ Changes committed
✓ Branch pushed
✓ Pull Request created
✓ CI started
✓ Tests passed
✓ PR ready
```

---

# 43. Implementation Report

Report page:

```text
Implementation Report

Task
Add JWT Authentication

Summary
Implemented JWT-based authentication.

Files Changed
7

Tests
42 / 42

Code Review
8.7 / 10

Security
No critical findings

AI Confidence
91%
```

Sections:

```text
Overview
Requirements
Implementation Plan
Files Changed
Tests
Code Review
Security
Risks
AI Evidence
```

---

# 44. AI Runs Page

Display all AI executions.

```text
AI Runs

Task                  Agent       Status
Add JWT Auth          Planner     ✓
Add JWT Auth          Coder       ✓
Add JWT Auth          Tester      ✓
Add JWT Auth          Reviewer    ✓
Fix payment bug       Planner     ⏳
```

Filters:

```text
Agent
Status
Project
Date
```

---

# 45. AI Run Detail

Display:

```text
Agent:
Planner

Status:
Completed

Duration:
4.2s

Model:
Configured LLM

Tools Used:
Code Search
File Retrieval

Result:
Implementation plan created
```

Do not expose private chain-of-thought.

---

# 46. Activity Page

Activity timeline:

```text
Today

14:32
PR #142 created

14:28
Code review completed

14:26
42 tests passed

14:21
Code generated

14:18
Plan approved
```

---

# 47. Notifications

Notifications should include:

```text
AI task completed
AI task failed
Plan ready for approval
Changes ready for review
Tests failed
PR created
CI failed
```

Example:

```text
🔔

Plan ready for approval
"Add JWT authentication"

2 minutes ago
```

---

# 48. Settings

Main settings:

```text
Profile
GitHub
AI
Security
Notifications
Projects
```

---

# 49. AI Settings

Allow configuration such as:

```text
AI Model
Embedding Model
Maximum Agent Retries
Maximum Context Size
```

Example:

```text
Maximum retries
[ 3 ]

Require approval before code generation
[ ✓ ]

Require approval before GitHub write
[ ✓ ]
```

---

# 50. GitHub Settings

Display:

```text
Connected Account

GitHub
username

Status:
● Connected

Permissions:
Repository access

[ Disconnect GitHub ]
```

---

# 51. Security Settings

Display:

```text
Authentication
Active Sessions
Connected Applications
GitHub Access
API Security
```

---

# 52. Empty States

Every major screen must have a useful empty state.

Example:

```text
No Projects Yet

Connect a GitHub repository and create
your first AI-powered engineering project.

[ Create Project ]
```

Avoid empty screens.

---

# 53. Loading States

Use skeletons for normal page loading.

For AI operations, use meaningful progress.

Bad:

```text
Loading...
```

Better:

```text
Analyzing repository...
Finding relevant code...
Building implementation plan...
```

---

# 54. Error States

Errors should be actionable.

Bad:

```text
Something went wrong.
```

Better:

```text
GitHub connection failed.

RepoPilot could not access the selected
repository.

Possible reason:
Your GitHub authorization may have expired.

[ Reconnect GitHub ]
```

---

# 55. AI Failure State

Example:

```text
AI Task Failed

RepoPilot could not generate a valid
implementation plan.

Reason:
The repository could not be indexed.

[ Retry ]
[ View Details ]
```

---

# 56. Confirmation Dialogs

Destructive actions require confirmation.

Examples:

```text
Delete Project
Disconnect GitHub
Cancel Task
Reject Changes
Delete Repository Connection
```

Example:

```text
Delete Project?

This will remove the project from RepoPilot.
The GitHub repository will not be deleted.

[ Cancel ] [ Delete Project ]
```

---

# 57. Toast Notifications

Use for lightweight feedback.

Examples:

```text
✓ Project created
✓ Plan approved
✓ Changes approved
✓ Branch created
✓ Pull Request created
```

Do not use toasts for important decisions that require user attention.

---

# 58. Responsive Design

The platform should support:

```text
Desktop
Tablet
Mobile
```

Desktop is the primary target because RepoPilot is a developer tool.

---

# 59. Desktop Layout

Minimum recommended design target:

```text
1440 × 900
```

Primary experience:

```text
Sidebar + Main Content + Optional Inspector
```

---

# 60. Tablet Layout

On smaller screens:

- Sidebar collapses.

- Secondary panels become drawers.

- Diff viewer becomes single-column.

- Tables become scrollable.


---

# 61. Mobile Layout

Mobile should support:

- Authentication.

- Dashboard.

- Task status.

- Notifications.

- Basic task review.


Advanced code diff editing/review should remain optimized for desktop.

---

# 62. Keyboard Shortcuts

Developer-oriented shortcuts:

```text
⌘ / Ctrl + K
Global Search

⌘ / Ctrl + Enter
Submit / Approve

Esc
Close Modal

G then P
Projects

G then T
Tasks

G then R
Repositories
```

Exact shortcuts may be refined during implementation.

---

# 63. Accessibility

The UI should follow WCAG-oriented accessibility practices.

Requirements:

- Keyboard navigation.

- Visible focus states.

- Semantic HTML.

- Accessible forms.

- Screen-reader-friendly labels.

- Sufficient color contrast.

- Do not rely on color alone.

- Accessible dialogs.

- Accessible notifications.


---

# 64. Component Design System

Reusable components should include:

### Navigation

```text
Sidebar
TopBar
Breadcrumbs
Tabs
```

### Forms

```text
Input
Textarea
Select
Checkbox
Radio
Button
```

### Data

```text
Table
Badge
Card
Metric
Timeline
```

### AI

```text
AIStatus
AIActivity
ConfidenceBadge
EvidencePanel
AgentStep
```

### Code

```text
CodeViewer
DiffViewer
FileTree
CodeSearch
```

### Workflow

```text
TaskTimeline
ApprovalPanel
TestResults
ReviewPanel
PRStatus
```

---

# 65. Status Design

Standard statuses:

```text
● Pending
● Running
● Waiting
● Success
● Warning
● Failed
● Cancelled
```

Every status must include both:

- Visual indicator.

- Text label.


Do not communicate state through color alone.

---

# 66. AI Visual Language

AI-related UI should have a consistent visual identity.

Examples:

```text
AI Plan
AI Suggestion
AI Review
AI Evidence
AI Confidence
AI Activity
```

Use one consistent AI accent rather than making every AI component visually different.

---

# 67. AI Evidence Component

Example:

```text
┌──────────────────────────────────────┐
│ AI Evidence                          │
├──────────────────────────────────────┤
│                                      │
│ SecurityConfig.java                  │
│ Lines 32–58                          │
│                                      │
│ AuthController.java                  │
│ Lines 15–42                          │
│                                      │
│ Existing JWT dependency detected.    │
└──────────────────────────────────────┘
```

Clicking an evidence item should open the relevant file/location.

---

# 68. AI Confidence Component

Example:

```text
AI Confidence

91%  High

Based on:
✓ Existing authentication layer
✓ JWT dependency
✓ User service
✓ Security configuration
```

---

# 69. Agent Workflow Visualization

The task page should visually represent:

```text
Analyzer
   ↓
Codebase
   ↓
Planner
   ↓
Approval
   ↓
Coder
   ↓
Tester
   ↓
Reviewer
   ↓
Approval
   ↓
GitHub
```

Current node should be highlighted.

Completed nodes should be marked complete.

Failed nodes should display the error state.

---

# 70. Code Review UX

Users should be able to navigate:

```text
Critical
Warnings
Suggestions
Security
```

Clicking an issue should navigate to the relevant diff location.

Example:

```text
⚠ Hardcoded JWT expiration
   → SecurityConfig.java:48
```

---

# 71. Test UX

Users should be able to:

- Expand test.

- View output.

- View logs.

- View failure details.

- See AI analysis.

- Trigger retry.


---

# 72. Pull Request UX

PR page should show:

```text
PR Information
Changes
Tests
AI Review
CI Status
Timeline
Implementation Report
```

---

# 73. Activity UX

Activity should be chronological.

Each activity:

```text
Icon
Action
Description
Timestamp
Related resource
```

Example:

```text
✓ Pull Request created
Add JWT authentication
#142
2 minutes ago
```

---

# 74. Navigation Rules

Users should always know:

```text
Where am I?
What project am I viewing?
What task am I working on?
What is the AI doing?
What requires my action?
```

Breadcrumb example:

```text
Projects / E-Commerce / Tasks / #124
```

---

# 75. Critical User Journey

The primary UX journey:

```text
Login
 ↓
Connect GitHub
 ↓
Create Project
 ↓
Select Repository
 ↓
Index Repository
 ↓
Create Task
 ↓
AI Analyzes
 ↓
Review Plan
 ↓
Approve
 ↓
AI Generates Code
 ↓
Review Diff
 ↓
Approve
 ↓
Run Tests
 ↓
AI Review
 ↓
Final Approval
 ↓
Create PR
 ↓
View PR
```

Every stage should clearly show:

```text
Current State
Next Action
Potential Problem
```

---

# 76. Primary CTA Hierarchy

The interface should have one dominant action per screen.

Examples:

Dashboard:

```text
New Task
```

Project:

```text
New Task
```

Plan:

```text
Approve Plan
```

Changes:

```text
Approve Changes
```

Final:

```text
Approve & Create PR
```

Avoid having five equally prominent buttons.

---

# 77. User Trust Design

The UI should make AI limitations visible.

Example:

```text
AI-generated suggestion

This change has not been applied to GitHub.

Review the diff before approving.
```

This reduces accidental actions and builds trust.

---

# 78. Dangerous Action UX

Actions affecting GitHub must have explicit context.

Instead of:

```text
[Submit]
```

Use:

```text
[ Approve & Create Pull Request ]
```

The button should clearly communicate the consequence.

---

# 79. Loading / Progress UX

AI tasks should provide meaningful progress.

Example:

```text
Analyzing repository             ✓
Finding relevant code            ✓
Creating implementation plan     ✓
Generating changes               ⏳
Running tests                    ○
Reviewing code                   ○
Creating Pull Request            ○
```

---

# 80. Real-Time Updates

When an AI task is running, the UI should update without requiring manual refresh.

Preferred technologies:

```text
SSE
or
WebSocket
```

Example:

```text
Task #124

Status:
Testing...

Last update:
Running JwtFilterTest
```

---

# 81. Error Recovery UX

Every recoverable error should provide an action.

Examples:

```text
GitHub expired
→ Reconnect

AI failed
→ Retry

Tests failed
→ Analyze / Fix

Index outdated
→ Re-index

PR creation failed
→ Retry
```

---

# 82. Onboarding

First-time users should see:

```text
Welcome to RepoPilot

1. Connect GitHub
2. Select a repository
3. Let RepoPilot index the codebase
4. Create your first engineering task
```

CTA:

```text
[ Connect GitHub ]
```

---

# 83. First Task Experience

After repository indexing:

```text
Your repository is ready.

Try something like:

"Add JWT authentication."

"Add pagination to the users API."

"Fix the payment timeout issue."

[ Create Your First Task ]
```

---

# 84. Design for Technical Interviews

The UI should make the project's technical capabilities visible during demos.

A good demo should allow the interviewer to see:

```text
Repository
 ↓
RAG
 ↓
Agent workflow
 ↓
Plan
 ↓
Code diff
 ↓
Tests
 ↓
Review
 ↓
PR
```

This makes the architecture understandable visually.

---

# 85. Demo Mode

Optional future feature:

```text
Demo Mode
```

A controlled sample repository can be used to demonstrate RepoPilot without connecting a personal GitHub account.

Example:

```text
Demo Repository
Spring Boot E-Commerce API
```

This makes interviews and presentations easier.

---

# 86. UX Performance Requirements

The UI should:

- Render initial navigation quickly.

- Use skeleton loading.

- Avoid unnecessary full-page refreshes.

- Use pagination for large lists.

- Lazy-load heavy code viewers.

- Stream AI progress.

- Avoid blocking the UI during AI workflows.


---

# 87. UI Error Boundaries

Frontend should gracefully handle:

- API failures.

- Network disconnects.

- Expired sessions.

- Invalid task states.

- AI service failures.

- GitHub errors.


---

# 88. Design Tokens

The implementation should define centralized tokens for:

```text
Colors
Typography
Spacing
Radius
Shadows
Borders
Transitions
Breakpoints
Z-index
```

Components should use tokens rather than arbitrary values.

---

# 89. Animation

Animations should be subtle.

Appropriate:

- Progress transitions.

- Status changes.

- Modal opening.

- Sidebar transitions.

- Loading indicators.


Avoid:

- Excessive bouncing.

- Decorative animations.

- Long transitions.


AI activity should feel alive but not distracting.

---

# 90. Final Screen Map

```text
PUBLIC
│
├── Landing
├── Login
├── Register
└── GitHub OAuth
│
AUTHENTICATED
│
├── Dashboard
│
├── Projects
│   ├── Project List
│   └── Project
│       ├── Overview
│       ├── Tasks
│       │   ├── Task List
│       │   └── Task Detail
│       │       ├── Issue
│       │       ├── Plan
│       │       ├── Changes
│       │       ├── Tests
│       │       ├── Review
│       │       └── PR
│       │
│       ├── Codebase
│       ├── AI Runs
│       ├── Pull Requests
│       ├── Activity
│       └── Settings
│
├── Global Tasks
├── Repositories
├── Pull Requests
├── AI Runs
├── Activity
│
└── Settings
    ├── Profile
    ├── GitHub
    ├── AI
    ├── Security
    └── Notifications
```

---

# 91. Final UX Architecture

```text
                           REPOPILOT
                               │
                ┌──────────────┴──────────────┐
                │                             │
             Dashboard                     Project
                                                │
                          ┌─────────────────────┼─────────────────────┐
                          │                     │                     │
                       Codebase               Tasks                 PRs
                                                │
                                                ▼
                                          Task Workspace
                                                │
                         ┌──────────────────────┼──────────────────────┐
                         │                      │                      │
                       Plan                  Changes                 Tests
                         │                      │                      │
                         └──────────────────────┼──────────────────────┘
                                                │
                                                ▼
                                             Review
                                                │
                                                ▼
                                          Final Approval
                                                │
                                                ▼
                                           GitHub PR
```

---

# 92. Core UX Philosophy

The entire application should follow this pattern:

```text
UNDERSTAND
    ↓
EXPLAIN
    ↓
PROPOSE
    ↓
SHOW
    ↓
VALIDATE
    ↓
ASK
    ↓
EXECUTE
    ↓
REPORT
```

RepoPilot should never make the user wonder:

> "What is the AI doing right now?"

or:

> "What exactly is going to happen if I click this?"

---

# 93. Final Design Goal

The final UI should feel like:

```text
GitHub
   +
Linear
   +
Modern AI Coding Tool
   +
Enterprise Engineering Dashboard
```

but with RepoPilot's own identity.

The product should communicate:

> **"This is an engineering system with AI capabilities."**

not:

> **"This is a chatbot with a dashboard."**

---

# 94. Design Completion Criteria

The UI/UX design is considered complete when the following are defined:

-  Application shell

-  Navigation

-  Authentication

-  GitHub connection

-  Dashboard

-  Project management

-  Repository explorer

-  Code search

-  Task creation

-  Task workspace

-  AI progress

-  AI understanding

-  Plan review

-  Code diff

-  Change explanations

-  Human approval

-  Test results

-  Self-correction

-  Code review

-  Security review

-  Final approval

-  Pull Request view

-  Implementation report

-  AI runs

-  Activity

-  Notifications

-  Settings

-  Loading states

-  Empty states

-  Error states

-  Confirmation dialogs

-  Responsive behavior

-  Accessibility

-  Keyboard shortcuts

-  Design system

-  AI visual language

-  Real-time progress

-  Error recovery

-  Onboarding

-  Demo mode