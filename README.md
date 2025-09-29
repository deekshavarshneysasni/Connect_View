# 📘 ConnectView

ConnectView is a **full-stack application** with:  
- **Frontend**: React (JavaScript, CSS)  
- **Backend**: Spring Boot (Java 17, REST APIs)  

---

## 🚀 Tech Stack

### Frontend
- React  
- JavaScript (ES6+)  
- CSS  
- Node.js **v22.15.0**  
- npm **v10.9.2**  

### Backend
- Spring Boot  
- Java **17 (Windows x64 installer)**  
- Maven (build tool, wrapper included)  

---

## 📂 Project Structure
```
connectview/
│── frontend/   # React application
│── backend/    # Spring Boot application
│── README.md   # Documentation
```

---

## ⚙️ Setup Instructions


### 2️⃣ Backend Setup (Spring Boot)

#### Prerequisites
- Install [Java 17] (Windows x64 installer).  
- Verify installation:

  java -version
  


#### Run Backend
Run the spring boot project


Backend runs at:  
👉 `http://localhost:8080`

---

### 3️⃣ Frontend Setup (React)

#### Prerequisites
- Install [Node.js v22.15.0](includes npm v10.9.2).  
- Verify installation:
  node -v
  npm -v


#### Install Dependencies
```bash
cd frontend
npm install
#### Run Frontend
cd frontend-dashboard    
# OR
npm run dev  

Frontend runs at:  
👉 `http://localhost:5173` (Vite)  

---

## 🔗 Example API Endpoints

| Method | Endpoint                | Description        |
|--------|-------------------------|--------------------|
| POST   | `/pbx/auth/login`       | PBX Login          |
| POST   | `/pbx/auth/logout`      | PBX Logout         |
| GET    | `/gdms/report?orgId=1`  | Fetch MAC Report   |

---


