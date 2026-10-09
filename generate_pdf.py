import os
from reportlab.lib.pagesizes import letter
from reportlab.lib import colors
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, HRFlowable, KeepTogether, ListFlowable, ListItem
)

def build_pdf(filename):
    doc = SimpleDocTemplate(
        filename,
        pagesize=letter,
        rightMargin=40,
        leftMargin=40,
        topMargin=40,
        bottomMargin=40
    )

    styles = getSampleStyleSheet()

    # Custom styles
    primary_color = colors.HexColor("#1A365D")   # Deep navy
    secondary_color = colors.HexColor("#2B6CB0") # Slate blue
    accent_color = colors.HexColor("#2C7A7B")    # Teal
    dark_neutral = colors.HexColor("#2D3748")    # Charcoal body text
    light_bg = colors.HexColor("#F7FAFC")        # Off white
    border_color = colors.HexColor("#E2E8F0")

    title_style = ParagraphStyle(
        'DocTitle',
        parent=styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=22,
        leading=26,
        textColor=primary_color,
        alignment=0,
        spaceAfter=4
    )

    subtitle_style = ParagraphStyle(
        'DocSubtitle',
        parent=styles['Normal'],
        fontName='Helvetica',
        fontSize=11,
        leading=15,
        textColor=secondary_color,
        spaceAfter=12
    )

    h1_style = ParagraphStyle(
        'Heading1Custom',
        parent=styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=13,
        leading=17,
        textColor=primary_color,
        spaceBefore=14,
        spaceAfter=6,
        keepWithNext=True
    )

    h2_style = ParagraphStyle(
        'Heading2Custom',
        parent=styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=10.5,
        leading=14,
        textColor=secondary_color,
        spaceBefore=8,
        spaceAfter=4,
        keepWithNext=True
    )

    body_style = ParagraphStyle(
        'BodyCustom',
        parent=styles['Normal'],
        fontName='Helvetica',
        fontSize=9,
        leading=13,
        textColor=dark_neutral,
        spaceAfter=5
    )

    bullet_style = ParagraphStyle(
        'BulletCustom',
        parent=styles['Normal'],
        fontName='Helvetica',
        fontSize=8.5,
        leading=12.5,
        textColor=dark_neutral,
        spaceAfter=3
    )

    code_style = ParagraphStyle(
        'CodeSnippet',
        parent=styles['Normal'],
        fontName='Courier',
        fontSize=8,
        leading=11,
        textColor=colors.HexColor("#1A202C")
    )

    table_cell_style = ParagraphStyle(
        'TableCell',
        parent=styles['Normal'],
        fontName='Helvetica',
        fontSize=8,
        leading=11,
        textColor=dark_neutral
    )

    table_header_style = ParagraphStyle(
        'TableHeader',
        parent=styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=8.5,
        leading=11.5,
        textColor=colors.white
    )

    callout_style = ParagraphStyle(
        'Callout',
        parent=styles['Normal'],
        fontName='Helvetica-Oblique',
        fontSize=8.5,
        leading=12,
        textColor=colors.HexColor("#2C5282")
    )

    elements = []

    # Title & Subtitle
    elements.append(Paragraph("FaceLock — Team Handoff & Implementation Guide", title_style))
    elements.append(Paragraph("Branch: <b>hardware-ready</b> &nbsp;|&nbsp; Repository: <b>github.com/Prinss-git/FaceLock</b>", subtitle_style))
    elements.append(HRFlowable(width="100%", thickness=1.5, color=primary_color, spaceAfter=10))

    # 1. Overview & Pulling
    elements.append(Paragraph("1. Getting Started & Git Branch Setup", h1_style))
    elements.append(Paragraph(
        "All updates for the face recognition server, hardware firmware, and locker link architecture have been committed and pushed to the <b>hardware-ready</b> branch. When pulling this repository, team members must switch to this branch:",
        body_style
    ))

    cmd_box = [
        [Paragraph("git fetch origin<br/>git checkout hardware-ready<br/>git pull origin hardware-ready", code_style)]
    ]
    t_cmd = Table(cmd_box, colWidths=[530])
    t_cmd.setStyle(TableStyle([
        ('BACKGROUND', (0,0), (-1,-1), colors.HexColor("#EDF2F7")),
        ('BOX', (0,0), (-1,-1), 1, border_color),
        ('TOPPADDING', (0,0), (-1,-1), 6),
        ('BOTTOMPADDING', (0,0), (-1,-1), 6),
        ('LEFTPADDING', (0,0), (-1,-1), 10),
        ('RIGHTPADDING', (0,0), (-1,-1), 10),
    ]))
    elements.append(t_cmd)
    elements.append(Spacer(1, 8))

    # 2. Secret Files & Environment Settings
    elements.append(Paragraph("2. Credentials & Config Files (Not Stored in Git)", h1_style))
    elements.append(Paragraph(
        "For security, credential files and API secrets are protected by <code>.gitignore</code>. Team members need to copy the corresponding templates and receive real credentials privately:",
        body_style
    ))

    sec_data = [
        [Paragraph("Target File", table_header_style), Paragraph("Source / Template", table_header_style), Paragraph("Contents / Notes", table_header_style)],
        [
            Paragraph("<b>app/google-services.json</b>", table_cell_style),
            Paragraph("Firebase Console", table_cell_style),
            Paragraph("Android configuration file generated in Firebase Project Settings.", table_cell_style)
        ],
        [
            Paragraph("<b>recognizer/serviceAccountKey.json</b>", table_cell_style),
            Paragraph("Firebase Console", table_cell_style),
            Paragraph("Service account private key (Project settings &rarr; Service accounts &rarr; Generate new private key).", table_cell_style)
        ],
        [
            Paragraph("<b>recognizer/.env</b>", table_cell_style),
            Paragraph("recognizer/.env.example", table_cell_style),
            Paragraph("Contains <code>FIREBASE_PROJECT_ID</code>, <code>STORAGE_BUCKET_NAME</code>, and shared <code>DEVICE_KEY</code>.", table_cell_style)
        ],
        [
            Paragraph("<b>firmware/simulator/.env</b>", table_cell_style),
            Paragraph("firmware/simulator/.env.example", table_cell_style),
            Paragraph("Web API key, Project ID, board user credentials (<code>board-m-001@...</code>), and locker ID <code>M-001</code>.", table_cell_style)
        ],
        [
            Paragraph("<b>firmware/FaceLock_ESP32CAM/secrets.h</b>", table_cell_style),
            Paragraph("secrets.example.h", table_cell_style),
            Paragraph("Wi-Fi credentials, Firebase Web API key, board auth, locker ID, and matching <code>SECRET_RECOGNIZE_KEY</code>.", table_cell_style)
        ],
    ]
    t_sec = Table(sec_data, colWidths=[140, 110, 280])
    t_sec.setStyle(TableStyle([
        ('BACKGROUND', (0,0), (-1,0), primary_color),
        ('GRID', (0,0), (-1,-1), 0.5, border_color),
        ('ROWBACKGROUNDS', (0,1), (-1,-1), [colors.white, light_bg]),
        ('TOPPADDING', (0,0), (-1,-1), 4),
        ('BOTTOMPADDING', (0,0), (-1,-1), 4),
    ]))
    elements.append(t_sec)
    elements.append(Spacer(1, 10))

    # 3. Step-by-Step Subsystem Setup
    elements.append(Paragraph("3. Subsystem Instructions", h1_style))

    # Firebase
    elements.append(Paragraph("A. Firebase Setup & Board User", h2_style))
    elements.append(Paragraph("• <b>Deploy Rules:</b> Run <code>firebase deploy --only firestore:rules,database</code> inside the <code>firebase/</code> folder.", bullet_style))
    elements.append(Paragraph("• <b>Create Board Auth:</b> In Firebase Authentication &rarr; Users &rarr; Add user: <code>board-m-001@facelock.device</code> with a secure password. Copy the resulting User UID.", bullet_style))
    elements.append(Paragraph("• <b>Link Board to Locker:</b> In Firestore, add doc <code>devices/&lt;UID&gt;</code> with fields: <code>lockerId = \"M-001\"</code>, <code>label = \"Demo board\"</code>.", bullet_style))

    # Mobile App
    elements.append(Paragraph("B. Android Mobile App", h2_style))
    elements.append(Paragraph("• Place <code>google-services.json</code> into the <code>app/</code> directory.", bullet_style))
    elements.append(Paragraph("• Open the project in Android Studio, let Gradle sync, then run <b>Build &rarr; Build APK(s)</b> and install on test devices.", bullet_style))
    elements.append(Paragraph("• Register 1 Admin account and 2 Member accounts (e.g. Alice & Bob). Create building & locker <b>M-001</b> in the app.", bullet_style))
    elements.append(Paragraph("• Enroll both members' face photos through the member face enrollment screen in the app.", bullet_style))

    # Recognizer
    elements.append(Paragraph("C. Face Recognizer Server (Python)", h2_style))
    elements.append(Paragraph("• Set up Python venv and install dependencies: <code>cd recognizer && python -m venv .venv && pip install -r requirements.txt</code>", bullet_style))
    elements.append(Paragraph("• Download ONNX face models (YuNet + SFace): <code>python download_models.py</code>", bullet_style))
    elements.append(Paragraph("• Start server: <code>python server.py</code> (monitors Firestore, converts enrolled user photos into 128-D face embeddings, and serves unlock requests).", bullet_style))
    elements.append(Paragraph("• Verify with webcam: <code>python webcam_test.py --uid &lt;uid&gt;</code> to verify MATCH vs NO MATCH.", bullet_style))

    # Simulator
    elements.append(Paragraph("D. Firmware Simulator (Node.js)", h2_style))
    elements.append(Paragraph("• Test complete locker workflow before physical hardware arrives: <code>cd firmware/simulator && npm install && node simulate.js</code>", bullet_style))
    elements.append(Paragraph("• Verifies heartbeat, real-time unlock commands, offline indicators, and event logging in the mobile app.", bullet_style))

    elements.append(Spacer(1, 10))

    # 4. Hardware Checklist & Preparation
    elements.append(Paragraph("4. Hardware Parts Checklist & Wiring", h1_style))
    elements.append(Paragraph(
        "Refer to <b>firmware/WIRING.md</b> for complete schematic diagrams. Essential hardware bill of materials:",
        body_style
    ))

    hw_items = [
        [Paragraph("Component", table_header_style), Paragraph("Specification / Purpose", table_header_style), Paragraph("Pin / Wiring Connection", table_header_style)],
        [Paragraph("ESP32-CAM", table_cell_style), Paragraph("AI Thinker module (OV2640 camera)", table_cell_style), Paragraph("Main controller (5V, GND)", table_cell_style)],
        [Paragraph("USB Adapter", table_cell_style), Paragraph("ESP32-CAM-MB micro-USB daughterboard", table_cell_style), Paragraph("For flashing & serial monitoring", table_cell_style)],
        [Paragraph("Relay Module", table_cell_style), Paragraph("5V 1-Channel Relay (active LOW)", table_cell_style), Paragraph("IN to GPIO 12 (or GPIO 2 fallback)", table_cell_style)],
        [Paragraph("Solenoid Lock", table_cell_style), Paragraph("12V DC solenoid latch mechanism", table_cell_style), Paragraph("Controlled via Relay COM & NO contacts", table_cell_style)],
        [Paragraph("Flyback Diode", table_cell_style), Paragraph("1N4007 diode across solenoid leads", table_cell_style), Paragraph("Band/Cathode to +12V, Anode to GND", table_cell_style)],
        [Paragraph("LCD Display", table_cell_style), Paragraph("1602 LCD with I2C backpack (0x27)", table_cell_style), Paragraph("SDA &rarr; GPIO 13, SCL &rarr; GPIO 14", table_cell_style)],
        [Paragraph("PIR Sensor", table_cell_style), Paragraph("HC-SR501 motion sensor", table_cell_style), Paragraph("OUT &rarr; GPIO 15 (power from 5V)", table_cell_style)],
        [Paragraph("Power Supply", table_cell_style), Paragraph("12V 2A wall adapter + LM2596 step-down", table_cell_style), Paragraph("Dial LM2596 to strictly 5.0V before connecting ESP32", table_cell_style)],
    ]
    t_hw = Table(hw_items, colWidths=[110, 190, 230])
    t_hw.setStyle(TableStyle([
        ('BACKGROUND', (0,0), (-1,0), primary_color),
        ('GRID', (0,0), (-1,-1), 0.5, border_color),
        ('ROWBACKGROUNDS', (0,1), (-1,-1), [colors.white, light_bg]),
        ('TOPPADDING', (0,0), (-1,-1), 3.5),
        ('BOTTOMPADDING', (0,0), (-1,-1), 3.5),
    ]))
    elements.append(t_hw)
    elements.append(Spacer(1, 10))

    # 5. Team Next Plans & Division of Roles
    elements.append(Paragraph("5. Next Plans & Action Distribution", h1_style))

    roles_data = [
        [Paragraph("Role / Member", table_header_style), Paragraph("Primary Focus", table_header_style), Paragraph("Deliverables / Immediate Next Steps", table_header_style)],
        [
            Paragraph("<b>Backend / Firebase Lead</b>", table_cell_style),
            Paragraph("Cloud rules, accounts & keys", table_cell_style),
            Paragraph("1. Deploy firestore rules & test Spark plan quotas.<br/>2. Create board auth account & link document in <code>devices/</code>.<br/>3. Securely provide <code>google-services.json</code> & keys to team.", table_cell_style)
        ],
        [
            Paragraph("<b>Mobile App Lead</b>", table_cell_style),
            Paragraph("Android App & enrollment UX", table_cell_style),
            Paragraph("1. Build APK and distribute to team.<br/>2. Create Admin and 2 Member accounts.<br/>3. Test face enrollment and photo upload on mobile.", table_cell_style)
        ],
        [
            Paragraph("<b>AI / ML Lead</b>", table_cell_style),
            Paragraph("Recognition engine & verification", table_cell_style),
            Paragraph("1. Run <code>download_models.py</code> and verify local ONNX files.<br/>2. Run <code>python server.py</code> to verify photo-to-embedding sync.<br/>3. Test matching using <code>webcam_test.py</code>.", table_cell_style)
        ],
        [
            Paragraph("<b>Hardware Lead</b>", table_cell_style),
            Paragraph("ESP32-CAM assembly & flashing", table_cell_style),
            Paragraph("1. Set LM2596 step-down output to 5.0V with multimeter.<br/>2. Assemble circuit per <code>WIRING.md</code> (tie all grounds together).<br/>3. Flash <code>FaceLock_ESP32CAM.ino</code> and verify serial heartbeat.", table_cell_style)
        ],
    ]
    t_roles = Table(roles_data, colWidths=[120, 140, 270])
    t_roles.setStyle(TableStyle([
        ('BACKGROUND', (0,0), (-1,0), primary_color),
        ('GRID', (0,0), (-1,-1), 0.5, border_color),
        ('ROWBACKGROUNDS', (0,1), (-1,-1), [colors.white, light_bg]),
        ('TOPPADDING', (0,0), (-1,-1), 4),
        ('BOTTOMPADDING', (0,0), (-1,-1), 4),
    ]))
    elements.append(t_roles)
    elements.append(Spacer(1, 10))

    # 6. Demonstration Day Checklist
    elements.append(Paragraph("6. Live Demonstration Day Checklist", h1_style))
    elements.append(Paragraph("Follow this sequential checklist for a seamless project presentation:", body_style))

    demo_steps = [
        "<b>Hotspot Setup:</b> Connect laptop, ESP32-CAM board, and testing phones to the same 2.4 GHz phone hotspot.",
        "<b>Server Startup:</b> Run <code>python server.py</code> on laptop and verify it displays <i>\"Listening for recognition requests\"</i>.",
        "<b>Firewall Rule:</b> Verify Windows Firewall allows incoming Python connections across the hotspot network.",
        "<b>Locker Online Check:</b> Power on ESP32-CAM and check the Android app to ensure locker <b>M-001</b> status turns green (Online).",
        "<b>Remote App Unlock:</b> Tap Remote Unlock in the app; confirm relay clicks, solenoid pulls in, and audit log records the unlock.",
        "<b>Authorized Face Test:</b> Stand in front of camera; sensor triggers, LCD displays <i>\"Recognizing...\"</i>, locker unlocks, LCD shows member name.",
        "<b>Unauthorized Face Test:</b> Show an unregistered face; LCD displays <i>\"Access Denied\"</i>, red indicator/buzzer alerts, attempt logged.",
        "<b>Offline Detection Test:</b> Disconnect board power; confirm mobile app indicates locker <i>\"Offline\"</i> within 2 minutes."
    ]

    for step in demo_steps:
        elements.append(Paragraph(f"&bull; {step}", bullet_style))

    doc.build(elements)
    print(f"Successfully generated PDF: {filename}")

if __name__ == '__main__':
    output_path = os.path.abspath("FaceLock_Team_Handoff_Guide.pdf")
    build_pdf(output_path)
